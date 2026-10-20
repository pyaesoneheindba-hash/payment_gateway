package com.paymentgateway.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.FixedHostPortGenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import org.testcontainers.images.builder.Transferable;

import tools.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;

/**
 * Real RocketMQ 5.x Proxy integration test, isolated from the developer's
 * running Docker Compose broker and PostgreSQL database.
 *
 * <p>Requires a local Docker daemon. The proxy uses a dynamically selected
 * host port mapped 1:1 because RocketMQ advertises that port in gRPC routing.
 * Containers are removed after the test; no persistent volumes are used.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RocketMqBrokerIntegrationTest {
    private static final String IMAGE = "apache/rocketmq:5.3.2";
    private static final String CLI = "/home/rocketmq/rocketmq-5.3.2/bin/mqadmin";
    private static final String TOPIC = "payment_lifecycle_v1";
    private static final String GROUP = "notification-payment-status-v1";

    private static Network network;
    private static PostgreSQLContainer<?> postgres;
    private static GenericContainer<?> nameserver;
    private static GenericContainer<?> broker;
    private static RocketMqClientManager client;
    private static JdbcTemplate jdbc;
    private static ObservingHandler handler;
    private static int proxyPort;
    private static ObjectMapper mapper;

    /**
     * Starts disposable PostgreSQL, RocketMQ NameServer, Broker and Proxy resources.
     *
     * @throws Exception when an isolated service cannot be started or initialized
     */
    @BeforeAll
    static void startIsolatedStack() throws Exception {
        try {
            network = Network.newNetwork();
            postgres = new PostgreSQLContainer<>("postgres:16-alpine");
            nameserver = new GenericContainer<>(IMAGE)
                    .withNetwork(network)
                    .withNetworkAliases("rmqnamesrv")
                    .withEnv("HEAP_OPTS", "-Xms128m -Xmx256m -Xmn64m")
                    .withExposedPorts(9876)
                    .withCommand("sh", "mqnamesrv")
                    .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
            // The 5.x Proxy returns the broker-advertised gRPC address to the
            // Java SDK. A random Docker port mapping alone is insufficient:
            // it advertises rmqbroker:8081, which Windows cannot resolve.
            // Use a free local port for BOTH the Proxy listener and Docker's
            // host binding, and advertise loopback only in this disposable test.
            proxyPort = findAvailableLoopbackPort();
            String proxyConfig = """
                    {
                      "rocketMQClusterName": "DefaultCluster",
                      "grpcServerPort": %d,
                      "remotingListenPort": 8080
                    }
                    """.formatted(proxyPort);
            broker = new FixedHostPortGenericContainer<>(IMAGE)
                    .withNetwork(network)
                    .withNetworkAliases("rmqbroker")
                    .withEnv("NAMESRV_ADDR", "rmqnamesrv:9876")
                    .withEnv("HEAP_OPTS", "-Xms256m -Xmx768m -Xmn128m -XX:MaxDirectMemorySize=256m")
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource("rocketmq/broker-it.conf"),
                            "/home/rocketmq/rocketmq-5.3.2/conf/broker-it.conf")
                    .withCopyToContainer(Transferable.of(proxyConfig),
                            "/home/rocketmq/rocketmq-5.3.2/conf/rmq-proxy-it.json")
                    .withFixedExposedPort(proxyPort, proxyPort)
                    .withCommand("sh", "mqbroker", "--enable-proxy", "-c",
                            "/home/rocketmq/rocketmq-5.3.2/conf/broker-it.conf",
                            "-pc", "/home/rocketmq/rocketmq-5.3.2/conf/rmq-proxy-it.json")
                    .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(3)));

            postgres.start();
            nameserver.start();
            broker.start();
            createRocketMqResources();
            System.err.println("[RocketMQ IT] Proxy endpoint: 127.0.0.1:" + proxyPort);
            System.err.println("[RocketMQ IT] Published Docker mapping: "
                    + broker.getHost() + ":" + broker.getMappedPort(proxyPort));
            initializeDatabase();

            mapper = new ObjectMapper();
            handler = new ObservingHandler(jdbc);
            client = startPaymentClient();
        } catch (Exception | Error failure) {
            printDiagnostics();
            stopIsolatedStack();
            throw failure;
        }
    }

    /**
     * Prints ephemeral test-container diagnostics before Testcontainers removes them.
     * This is deliberately read-only and never inspects the running dev stack.
     */
    private static void printDiagnostics() {
        System.err.println("\n========== ISOLATED ROCKETMQ DIAGNOSTICS ==========");
        if (broker != null && broker.isRunning()) {
            try {
                System.err.println("[Broker/Proxy container stdout/stderr]\n"
                        + tail(broker.getLogs(), 12000));
                String diagnosticCommand = """
                        echo '--- Test Proxy configuration ---';
                        cat /home/rocketmq/rocketmq-5.3.2/conf/rmq-proxy-it.json;
                        echo '--- Test Broker configuration ---';
                        grep -E '^(brokerIP1|namesrvAddr|brokerName|brokerClusterName)=' /home/rocketmq/rocketmq-5.3.2/conf/broker-it.conf;
                        echo '--- Broker topic route ---';
                        sh /home/rocketmq/rocketmq-5.3.2/bin/mqadmin topicRoute -n rmqnamesrv:9876 -t payment_lifecycle_v1 2>&1;
                        echo '--- Proxy logs (last 100 lines per file, max 3 files) ---';
                        find /home/rocketmq /root -type f \\( -name 'proxy.log' -o -name 'proxy_error.log' -o -name 'broker.log' \\) 2>/dev/null | head -3 | while read f; do echo "LOG: $f"; tail -n 100 "$f"; done
                        """;
                var diagnostics = broker.execInContainer("sh", "-c", diagnosticCommand);
                System.err.println(tail(diagnostics.getStdout(), 24000));
                System.err.println(tail(diagnostics.getStderr(), 3000));
            } catch (Exception diagnosticFailure) {
                System.err.println("[Diagnostics unavailable] " + diagnosticFailure);
            }
        }
        if (nameserver != null && nameserver.isRunning()) {
            try {
                System.err.println("[NameServer stdout/stderr]\n"
                        + tail(nameserver.getLogs(), 4000));
            } catch (Exception diagnosticFailure) {
                System.err.println("[NameServer diagnostics unavailable] " + diagnosticFailure);
            }
        }
        System.err.println("========== END ISOLATED ROCKETMQ DIAGNOSTICS ==========\n");
    }

    /**
     * Limits diagnostic output to the last characters to keep test reports readable.
     *
     * @param value text to trim
     * @param limit maximum number of characters to retain
     * @return final portion of the text
     */
    private static String tail(String value, int limit) {
        return value == null ? "" : value.substring(Math.max(0, value.length() - limit));
    }

    /**
     * Chooses a free local TCP port for the isolated Proxy's advertised address.
     * The socket is released before Docker binds the port; a rare concurrent
     * allocation can still cause a clear container startup failure.
     *
     * @return available loopback port
     * @throws IOException if the local port cannot be allocated
     */
    private static int findAvailableLoopbackPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    /** Stops the temporary services and releases the test network. */
    @AfterAll
    static void stopIsolatedStack() {
        if (client != null) {
            client.close();
            client = null;
        }
        if (broker != null) {
            broker.stop();
            broker = null;
        }
        if (nameserver != null) {
            nameserver.stop();
            nameserver = null;
        }
        if (postgres != null) {
            postgres.stop();
            postgres = null;
        }
        if (network != null) {
            network.close();
            network = null;
        }
    }

    /**
     * Publishes two real broker messages with one event ID and verifies inbox deduplication.
     *
     * @throws Exception when the broker, serializer or database fails
     */
    @Test
    @Order(1)
    void realRocketMqTransportConsumesAndDeduplicatesPaymentStatusEvents() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO merchant_webhook_endpoint(endpoint_id, merchant_id, event_type, status)
                VALUES (?, ?, 'PAYMENT_STATUS_CHANGED', 'ACTIVE')
                """, endpointId, merchantId);

        PaymentLifecycleMessage event = new PaymentLifecycleMessage(
                eventId, "PAYMENT", paymentId, "PAYMENT_STATUS_CHANGED", 1,
                Instant.now(), Map.of("merchantId", merchantId.toString(), "status", "SUCCEEDED"));
        byte[] body = mapper.writeValueAsBytes(event);

        // First delivery must create one inbox row and one pending webhook.
        client.send(TOPIC, "PAYMENT_STATUS_CHANGED", eventId.toString(), body);
        await("first RocketMQ delivery", () -> handler.calls.get() >= 1);
        assertEquals(List.of(true), handler.results);
        assertEquals(1L, count("SELECT count(*) FROM inbox_event"));
        assertEquals(1L, count("SELECT count(*) FROM webhook_delivery"));
        assertEquals(1L, count("SELECT count(*) FROM inbox_event WHERE processed_at IS NOT NULL"));

        // This is a second *real broker message* with the same business event ID.
        // Wait for the second handler invocation so a false-positive (unconsumed
        // duplicate) cannot make the test pass.
        client.send(TOPIC, "PAYMENT_STATUS_CHANGED", eventId.toString(), body);
        await("duplicate RocketMQ delivery", () -> handler.calls.get() >= 2);
        assertEquals(List.of(true, false), handler.results);
        assertEquals(1L, count("SELECT count(*) FROM inbox_event"));
        assertEquals(1L, count("SELECT count(*) FROM webhook_delivery"));
        assertEquals(1L, count("SELECT count(*) FROM webhook_delivery WHERE status='PENDING'"));
    }

    /**
     * The production listener must report FAILURE on a transient exception.
     * Verify that the isolated broker actually redelivers the SAME message,
     * rather than proving only that a second explicit publication works.
     * The simulated failure happens before database writes, so this does not
     * test transactional rollback after a partial database update.
     *
     * @throws Exception when the broker, serialization or retry fails
     */
    @Test
    @Order(2)
    void transientConsumerFailureTriggersActualBrokerRedelivery() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO merchant_webhook_endpoint(endpoint_id, merchant_id, event_type, status)
                VALUES (?, ?, 'PAYMENT_STATUS_CHANGED', 'ACTIVE')
                """, endpointId, merchantId);

        handler.failOneAttemptFor(eventId);
        PaymentLifecycleMessage event = new PaymentLifecycleMessage(
                eventId, "PAYMENT", paymentId, "PAYMENT_STATUS_CHANGED", 1,
                Instant.now(), Map.of("merchantId", merchantId.toString(), "status", "SUCCEEDED"));

        // Publish ONCE; any second handler invocation is a RocketMQ retry.
        client.send(TOPIC, "PAYMENT_STATUS_CHANGED", eventId.toString(), mapper.writeValueAsBytes(event));
        await("initial simulated consumer failure",
                () -> handler.failedAttemptsFor(eventId) == 1, Duration.ofSeconds(30));
        await("broker redelivery and successful inbox commit",
                () -> handler.attemptsFor(eventId) >= 2
                        && countForEvent("inbox_event", eventId) == 1L,
                Duration.ofSeconds(150));

        assertEquals(1, handler.failedAttemptsFor(eventId));
        assertTrue(handler.attemptsFor(eventId) >= 2, "Broker did not retry the failed message");
        assertEquals(1L, countForEvent("inbox_event", eventId));
        assertEquals(1L, countForEvent("webhook_delivery", eventId));
        Long processed = jdbc.queryForObject(
                "SELECT count(*) FROM inbox_event WHERE event_id=? AND processed_at IS NOT NULL",
                Long.class, eventId);
        assertNotNull(processed);
        assertEquals(1L, processed.longValue());
    }

    /**
     * Verify that an event published while the consumer is stopped is
     * delivered after a new PushConsumer joins the same existing group.
     * This exercises offline/backlog recovery, not a forced broker crash.
     *
     * @throws Exception if the producer or restarted consumer cannot recover
     */
    @Test
    @Order(3)
    void newConsumerResumesAfterShutdownAndConsumesOfflineMessage() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO merchant_webhook_endpoint(endpoint_id, merchant_id, event_type, status)
                VALUES (?, ?, 'PAYMENT_STATUS_CHANGED', 'ACTIVE')
                """, endpointId, merchantId);
        PaymentLifecycleMessage event = new PaymentLifecycleMessage(
                eventId, "PAYMENT", paymentId, "PAYMENT_STATUS_CHANGED", 1,
                Instant.now(), Map.of("merchantId", merchantId.toString(), "status", "SUCCEEDED"));

        // Stop BOTH client components; no test-group consumer should remain.
        client.close();
        client = null;
        publishWithoutConsumer(eventId, mapper.writeValueAsBytes(event));
        assertEquals(0L, countForEvent("inbox_event", eventId),
                "The offline event was consumed before the consumer restarted");

        client = startPaymentClient();
        await("new consumer to process an event published while offline",
                () -> countForEvent("inbox_event", eventId) == 1L,
                Duration.ofSeconds(120));
        assertEquals(1, handler.attemptsFor(eventId));
        assertEquals(1L, countForEvent("inbox_event", eventId));
        assertEquals(1L, countForEvent("webhook_delivery", eventId));
    }

    /**
     * Constructs the same production RocketMQ client against the isolated Proxy.
     *
     * @return started payment-client instance
     * @throws Exception if the producer or PushConsumer cannot initialize
     */
    private static RocketMqClientManager startPaymentClient() throws Exception {
        RocketMqProperties properties = new RocketMqProperties();
        properties.setEnabled(true);
        properties.setEndpoints("127.0.0.1:" + proxyPort);
        properties.setSslEnabled(false);
        properties.setTopic(TOPIC);
        properties.setConsumerGroup(GROUP);
        RocketMqClientManager paymentClient = new RocketMqClientManager(properties, handler, mapper);
        paymentClient.start();
        return paymentClient;
    }

    /**
     * Publishes an event using a producer without starting any consumer.
     *
     * @param eventId unique business event identifier
     * @param body serialized event body
     * @throws Exception if the standalone producer cannot send
     */
    private static void publishWithoutConsumer(UUID eventId, byte[] body) throws Exception {
        ClientServiceProvider provider = ClientServiceProvider.loadService();
        ClientConfiguration config = ClientConfiguration.newBuilder()
                .setEndpoints("127.0.0.1:" + proxyPort)
                .enableSsl(false)
                .build();
        Producer standalone = provider.newProducerBuilder()
                .setClientConfiguration(config)
                .setTopics(TOPIC)
                .build();
        try {
            Message message = provider.newMessageBuilder().setTopic(TOPIC)
                    .setTag("PAYMENT_STATUS_CHANGED")
                    .setKeys(eventId.toString()).setBody(body).build();
            standalone.send(message);
        } finally {
            standalone.close();
        }
    }

    /**
     * Counts event-specific records, avoiding cross-test total assumptions.
     *
     * @param table one of the isolated event tables
     * @param eventId event identifier to count
     * @return number of matching rows
     */
    private static long countForEvent(String table, UUID eventId) {
        if (!"inbox_event".equals(table) && !"webhook_delivery".equals(table)) {
            throw new IllegalArgumentException("Unsupported isolated test table: " + table);
        }
        Long value = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE event_id=?",
                Long.class, eventId);
        assertNotNull(value);
        return value;
    }

    /**
     * Counts records using the disposable PostgreSQL database.
     *
     * @param sql a count query for the isolated test schema
     * @return the row count
     */
    private static long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        assertNotNull(value);
        return value;
    }

    /**
     * Waits for asynchronous consumption without relying on fixed sleeps.
     *
     * @param description label for a failed wait
     * @param condition condition to poll
     * @throws InterruptedException if the test thread is interrupted
     */
    private static void await(String description, BooleanSupplier condition) throws InterruptedException {
        await(description, condition, Duration.ofSeconds(60));
    }

    /**
     * Waits for asynchronous consumption with a scenario-specific timeout.
     *
     * @param description label for a failed wait
     * @param condition condition to poll
     * @param timeout upper bound for this asynchronous test wait
     * @throws InterruptedException if the test thread is interrupted
     */
    private static void await(String description, BooleanSupplier condition, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        assertTrue(condition.getAsBoolean(), "Timed out waiting for " + description
                + ". Check RocketMQ Proxy and notification consumer logs.");
    }

    /**
     * Waits for the isolated broker and creates its topic and consumer group.
     *
     * @throws Exception if broker administration fails
     */
    private static void createRocketMqResources() throws Exception {
        // A listening Proxy port can precede broker registration with NameServer.
        // Wait for broker-a to appear before provisioning topic and consumer group.
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        boolean registered = false;
        while (System.nanoTime() < deadline) {
            var result = broker.execInContainer("sh", CLI, "clusterList", "-n", "rmqnamesrv:9876");
            if (result.getExitCode() == 0 && result.getStdout().contains("broker-a")) {
                registered = true;
                break;
            }
            Thread.sleep(1000);
        }
        assertTrue(registered, "Isolated RocketMQ broker did not register with NameServer");
        runMqAdmin("updateTopic", "-n", "rmqnamesrv:9876", "-c", "DefaultCluster", "-t", TOPIC);
        runMqAdmin("updateSubGroup", "-n", "rmqnamesrv:9876", "-c", "DefaultCluster", "-g", GROUP);
    }

    /**
     * Runs an administrative command only inside the disposable broker container.
     *
     * @param args command and arguments passed to mqadmin
     * @throws Exception if execution in the container fails
     */
    private static void runMqAdmin(String... args) throws Exception {
        String[] command = new String[args.length + 2];
        command[0] = "sh";
        command[1] = CLI;
        System.arraycopy(args, 0, command, 2, args.length);
        var result = broker.execInContainer(command);
        assertEquals(0, result.getExitCode(),
                "mqadmin " + args[0] + " failed: " + result.getStdout() + " " + result.getStderr());
    }

    /** Creates the minimal consumer and webhook tables in disposable PostgreSQL. */
    private static void initializeDatabase() {
        DriverManagerDataSource datasource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(datasource);
        jdbc.execute("""
                CREATE TABLE inbox_event (
                    consumer_name VARCHAR(128) NOT NULL,
                    event_id UUID NOT NULL,
                    message_id VARCHAR(255),
                    event_type VARCHAR(128) NOT NULL,
                    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    processed_at TIMESTAMPTZ,
                    PRIMARY KEY (consumer_name, event_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE merchant_webhook_endpoint (
                    endpoint_id UUID PRIMARY KEY,
                    merchant_id UUID NOT NULL,
                    event_type VARCHAR(128) NOT NULL,
                    status VARCHAR(16) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE webhook_delivery (
                    delivery_id UUID PRIMARY KEY,
                    event_id UUID NOT NULL,
                    endpoint_id UUID NOT NULL,
                    merchant_id UUID NOT NULL,
                    payment_id UUID NOT NULL,
                    event_type VARCHAR(128) NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    attempt_count INTEGER NOT NULL,
                    next_attempt_at TIMESTAMPTZ NOT NULL,
                    version BIGINT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL,
                    UNIQUE (event_id, endpoint_id)
                )
                """);
    }

    /** Records completed invocations of the real payment-status event handler. */
    private static final class ObservingHandler extends PaymentStatusEventHandler {
        private final AtomicInteger calls = new AtomicInteger();
        private final List<Boolean> results = new CopyOnWriteArrayList<>();
        private final Map<UUID, AtomicInteger> attempts = new ConcurrentHashMap<>();
        private final Map<UUID, AtomicInteger> failedAttempts = new ConcurrentHashMap<>();
        private final AtomicReference<UUID> failOnceEventId = new AtomicReference<>();

        /**
         * Creates the observing handler.
         *
         * @param jdbc isolated test database access
         */
        private ObservingHandler(JdbcTemplate jdbc) {
            super(jdbc);
        }

        /**
         * Delegates to the production handler and records the completed result.
         *
         * @param event incoming payment event
         * @param rocketMqMessageId transport message identifier
         * @return whether the event was first processed
         */
        @Override
        public boolean handle(PaymentLifecycleMessage event, String rocketMqMessageId) {
            attempts.computeIfAbsent(event.eventId(), ignored -> new AtomicInteger())
                    .incrementAndGet();
            // AtomicReference.compareAndSet compares object identity, so compare UUID
            // value before clearing the one-shot failure flag atomically.
            UUID target = failOnceEventId.get();
            if (target != null && target.equals(event.eventId())
                    && failOnceEventId.compareAndSet(target, null)) {
                failedAttempts.computeIfAbsent(event.eventId(), ignored -> new AtomicInteger())
                        .incrementAndGet();
                throw new IllegalStateException("Simulated one-time consumer failure before database write");
            }
            boolean inserted = super.handle(event, rocketMqMessageId);
            results.add(inserted);
            calls.incrementAndGet();
            return inserted;
        }

        /**
         * Selects exactly one event to fail on its first consumer invocation.
         *
         * @param eventId event to fail once before any database write
         */
        private void failOneAttemptFor(UUID eventId) {
            failOnceEventId.set(eventId);
        }

        /**
         * Returns the number of listener invocations for an event.
         *
         * @param eventId event to inspect
         * @return number of invocations
         */
        private int attemptsFor(UUID eventId) {
            AtomicInteger counter = attempts.get(eventId);
            return counter == null ? 0 : counter.get();
        }

        /**
         * Returns the number of injected failures for an event.
         *
         * @param eventId event to inspect
         * @return number of simulated failures
         */
        private int failedAttemptsFor(UUID eventId) {
            AtomicInteger counter = failedAttempts.get(eventId);
            return counter == null ? 0 : counter.get();
        }
    }
}
