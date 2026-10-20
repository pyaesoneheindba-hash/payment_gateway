package com.paymentgateway.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;


import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.FixedHostPortGenericContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import org.testcontainers.images.builder.Transferable;

import com.paymentgateway.common.Hashing;
import com.paymentgateway.common.ProviderCapability;
import com.paymentgateway.common.ProviderOutcome;
import com.paymentgateway.contract.payment.CreatePaymentCommand;
import com.paymentgateway.contract.provider.EnquirePaymentCommand;
import com.paymentgateway.contract.provider.EnquirePaymentResult;
import com.paymentgateway.contract.provider.InitiatePaymentCommand;
import com.paymentgateway.contract.provider.InitiatePaymentResult;
import com.paymentgateway.contract.provider.ProviderRpcService;
import com.paymentgateway.core.persistence.repo.PaymentRepository;
import com.paymentgateway.core.service.PaymentPersistenceService;
import com.paymentgateway.core.service.PaymentRpcServiceImpl;
import com.paymentgateway.core.service.ProviderClientRegistry;
import com.sun.net.httpserver.HttpServer;

import jakarta.persistence.EntityManagerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Stage 7.2–7.5: real payment-core transaction, outbox, RocketMQ 5.x Proxy,
 * idempotent inbox, webhook queue, signed HTTP delivery, and a *mocked* bank.
 *
 * This is an in-process service integration test, NOT an external gateway/PGSIG1
 * or networked Dubbo transport test. It creates only disposable containers and
 * a loopback HTTP receiver; production bank adapters are never instantiated.
 */
class Stage7PaymentLifecycleTest {
    private static final String ROCKETMQ_IMAGE = "apache/rocketmq:5.3.2";
    private static final String MQADMIN = "/home/rocketmq/rocketmq-5.3.2/bin/mqadmin";
    private static final String TOPIC = "payment_lifecycle_v1";
    private static final String GROUP = "stage7-notification-payment-status-v1";
    private static final String PROVIDER = "ABANK_APLUS";

    private static PostgreSQLContainer<?> postgres;
    private static Network network;
    private static GenericContainer<?> nameserver;
    private static GenericContainer<?> broker;
    private static AnnotationConfigApplicationContext spring;
    private static RocketMqClientManager client;
    private static WebhookDispatcher dispatcher;
    private static HttpServer receiver;
    private static Path signingPrivateKeyFile;
    private static KeyPair signingKeys;
    private static int proxyPort;
    private static final ConcurrentLinkedQueue<ReceivedWebhook> received = new ConcurrentLinkedQueue<>();

    /** Starts only disposable resources, applies the real Flyway migrations and creates JPA services. */
    @BeforeAll
    static void startIsolatedStack() throws Exception {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        javax.sql.DataSource testDatabase = database();
        Flyway.configure().dataSource(testDatabase).locations("classpath:db/migration").load().migrate();

        // Explicit Spring configuration: no Nacos discovery, Dubbo registry,
        // gateway, bank HTTP client or background scheduler is started.
        spring = new AnnotationConfigApplicationContext(TestPersistenceConfig.class);

        network = Network.newNetwork();
        nameserver = new GenericContainer<>(ROCKETMQ_IMAGE)
                .withNetwork(network).withNetworkAliases("rmqnamesrv")
                .withEnv("HEAP_OPTS", "-Xms128m -Xmx256m -Xmn64m")
                .withExposedPorts(9876).withCommand("sh", "mqnamesrv")
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
        proxyPort = freeLocalPort();
        String proxyConfig = """
                {
                  "rocketMQClusterName": "DefaultCluster",
                  "grpcServerPort": %d,
                  "remotingListenPort": 8080
                }
                """.formatted(proxyPort);
        broker = new FixedHostPortGenericContainer<>(ROCKETMQ_IMAGE)
                .withNetwork(network).withNetworkAliases("rmqbroker")
                .withEnv("NAMESRV_ADDR", "rmqnamesrv:9876")
                .withEnv("HEAP_OPTS", "-Xms256m -Xmx768m -Xmn128m -XX:MaxDirectMemorySize=256m")
                .withCopyFileToContainer(MountableFile.forClasspathResource("rocketmq/broker-it.conf"),
                        "/home/rocketmq/rocketmq-5.3.2/conf/broker-it.conf")
                .withCopyToContainer(Transferable.of(proxyConfig),
                        "/home/rocketmq/rocketmq-5.3.2/conf/rmq-proxy-it.json")
                .withFixedExposedPort(proxyPort, proxyPort)
                .withCommand("sh", "mqbroker", "--enable-proxy", "-c",
                        "/home/rocketmq/rocketmq-5.3.2/conf/broker-it.conf", "-pc",
                        "/home/rocketmq/rocketmq-5.3.2/conf/rmq-proxy-it.json")
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(3)));
        nameserver.start();
        broker.start();
        configureBroker();

        // Disposable merchant webhook signing identity. Never reads the dev signing key.
        signingKeys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        signingPrivateKeyFile = Files.createTempFile("stage7-webhook-", "-private.pem");
        Files.writeString(signingPrivateKeyFile, "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(signingKeys.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n", StandardCharsets.US_ASCII);

        receiver = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        receiver.createContext("/stage7-webhook", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String eventId = exchange.getRequestHeaders().getFirst("X-Payment-Event-Id");
            String timestamp = exchange.getRequestHeaders().getFirst("X-Payment-Timestamp");
            String digest = exchange.getRequestHeaders().getFirst("Content-Digest");
            String signature = exchange.getRequestHeaders().getFirst("X-Payment-Signature");
            boolean valid = signatureValid(eventId, timestamp, digest, signature, body);
            if (valid) received.add(new ReceivedWebhook(UUID.fromString(eventId), body));
            exchange.sendResponseHeaders(valid ? 204 : 401, -1);
            exchange.close();
        });
        receiver.start();

        RocketMqProperties rocketProperties = new RocketMqProperties();
        rocketProperties.setEnabled(true);
        rocketProperties.setEndpoints("127.0.0.1:" + proxyPort);
        rocketProperties.setTopic(TOPIC);
        rocketProperties.setPaymentStatusTag("PAYMENT_STATUS_CHANGED");
        rocketProperties.setConsumerGroup(GROUP);
        ObjectMapper mapper = new ObjectMapper();
        client = new RocketMqClientManager(rocketProperties,
                spring.getBean(PaymentStatusEventHandler.class), mapper);
        client.start();
        // The production publisher is invoked explicitly. No scheduler is started.
        publisher = new RocketMqOutboxPublisher(spring.getBean(JdbcTemplate.class), mapper,
                rocketProperties, client, noPortalPublisher());

        WebhookNotificationProperties webhookProperties = new WebhookNotificationProperties();
        webhookProperties.setEnabled(true);
        webhookProperties.setSigningKeyId("stage7-test-only");
        webhookProperties.setPrivateKeyPath(signingPrivateKeyFile.toString());
        webhookProperties.setAllowHttp(true);
        webhookProperties.setAllowPrivateAddresses(true); // isolated loopback receiver only
        dispatcher = new WebhookDispatcher(spring.getBean(JdbcTemplate.class), mapper, webhookProperties,
                new WebhookSigner(webhookProperties), new WebhookUrlGuard(webhookProperties));
    }

    private static RocketMqOutboxPublisher publisher;

    /** Creates a payment using a mock bank, verifies the status, then delivers real signed webhooks. */
    @Test
    void mockPaymentToSignedMerchantWebhookUsesDurableEndToEndPath() throws Exception {
        JdbcTemplate jdbc = spring.getBean(JdbcTemplate.class);
        UUID merchantId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO merchant(merchant_id,merchant_code,merchant_name,status,environment,default_currency)
                VALUES (?,?,'Stage 7 disposable merchant','ACTIVE','UAT','MMK')
                """, merchantId, "S7-" + merchantId.toString().substring(0, 12));
        jdbc.update("""
                INSERT INTO merchant_webhook_endpoint(endpoint_id,merchant_id,event_type,url,description,status)
                VALUES (?,?,'PAYMENT_STATUS_CHANGED',?,'Stage 7 isolated receiver','ACTIVE')
                """, endpointId, merchantId,
                "http://127.0.0.1:" + receiver.getAddress().getPort() + "/stage7-webhook");

        ProviderRpcService fakeBank = mock(ProviderRpcService.class);
        ProviderClientRegistry registry = mock(ProviderClientRegistry.class);
        when(registry.requireBase(PROVIDER)).thenReturn(fakeBank);
        when(registry.require(PROVIDER, ProviderCapability.ENQUIRY, ProviderRpcService.class))
                .thenReturn(fakeBank);
        when(fakeBank.initiate(any(InitiatePaymentCommand.class))).thenAnswer(invocation -> {
            InitiatePaymentCommand c = invocation.getArgument(0);
            return new InitiatePaymentResult(ProviderOutcome.PENDING, PROVIDER, c.providerOrderId(),
                    "stage7-provider-reference", "stage7-request", "NONE", null, null,
                    Map.of(), "PENDING", "Synthetic bank initiation");
        });
        when(fakeBank.enquire(any(EnquirePaymentCommand.class))).thenAnswer(invocation -> {
            EnquirePaymentCommand c = invocation.getArgument(0);
            return new EnquirePaymentResult(ProviderOutcome.SUCCESS, c.providerOrderId(),
                    "stage7-provider-reference", null, "SUCCESS", "stage7-transaction", null, null,
                    null, "STAGE7", "Synthetic bank enquiry confirmed");
        });

        // Real payment-core persistence and transactional outbox; fake bank only.
        PaymentRpcServiceImpl core = new PaymentRpcServiceImpl(
                spring.getBean(PaymentPersistenceService.class), registry);
        String order = "S7-" + UUID.randomUUID();
        var created = core.createPayment(new CreatePaymentCommand(merchantId, order, 1200L,
                "MMK", "Stage 7 test only", PROVIDER, Map.of(), UUID.randomUUID().toString(),
                Hashing.sha256(order)));
        UUID paymentId = created.paymentId();
        assertNotNull(paymentId);
        assertEquals("PENDING", paymentStatus(jdbc, paymentId));
        assertEquals(1L, eventCount(jdbc, paymentId, "PAYMENT_CREATED"));
        assertEquals(1L, eventCount(jdbc, paymentId, "PAYMENT_STATUS_CHANGED"));

        core.reconcilePayment(merchantId, paymentId);
        assertEquals("SUCCEEDED", paymentStatus(jdbc, paymentId));
        assertEquals(2L, eventCount(jdbc, paymentId, "PAYMENT_STATUS_CHANGED"));
        verify(fakeBank, times(1)).initiate(any(InitiatePaymentCommand.class));
        verify(fakeBank, times(1)).enquire(any(EnquirePaymentCommand.class));

        // Real outbox publisher -> ephemeral RocketMQ Proxy -> real PushConsumer.
        await("outbox publication", () -> {
            // A just-started proxy may require a short readiness grace period;
            // let the real outbox retry policy, not synthetic duplicate sends, recover.
            publisher.publish();
            return count(jdbc,
                    "SELECT count(*) FROM outbox_event WHERE aggregate_id=? AND status='PUBLISHED'",
                    paymentId) == 3;
        });
        await("inbox and queued webhook fan-out", () -> count(jdbc,
                "SELECT count(*) FROM inbox_event i JOIN outbox_event o ON o.event_id=i.event_id "
                + "WHERE o.aggregate_id=? AND i.processed_at IS NOT NULL", paymentId) == 2
                && count(jdbc, "SELECT count(*) FROM webhook_delivery WHERE payment_id=?", paymentId) == 2);

        // Real webhook queue -> signed PGWH1 HTTP requests to local receiver.
        dispatcher.deliverDueWebhooks();
        await("signed HTTP webhooks delivered", () -> count(jdbc,
                "SELECT count(*) FROM webhook_delivery WHERE payment_id=? AND status='DELIVERED'", paymentId) == 2);
        assertEquals(2, received.size(), "Only two PAYMENT_STATUS_CHANGED webhooks are expected");
        Set<UUID> receivedIds = received.stream().map(ReceivedWebhook::eventId)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(2, receivedIds.size(), "Every eventId must be stable and unique per state change");
        UUID succeededId = jdbc.queryForObject("""
                SELECT event_id FROM outbox_event WHERE aggregate_id=?
                  AND event_type='PAYMENT_STATUS_CHANGED' AND payload->>'status'='SUCCEEDED'
                """, UUID.class, paymentId);
        assertTrue(receivedIds.contains(succeededId), "Merchant did not receive verified SUCCEEDED status");
    }

    /** Releases only the ephemeral stage-7 resources. */
    @AfterAll
    static void closeIsolatedStack() {
        if (dispatcher != null) dispatcher.shutdown();
        if (client != null) client.close();
        if (receiver != null) receiver.stop(0);
        if (spring != null) spring.close();
        if (broker != null) broker.stop();
        if (nameserver != null) nameserver.stop();
        if (network != null) network.close();
        if (postgres != null) postgres.stop();
        if (signingPrivateKeyFile != null) {
            try { Files.deleteIfExists(signingPrivateKeyFile); }
            catch (IOException e) { throw new IllegalStateException("Cannot remove test-only signing key", e); }
        }
    }

    /** Minimal real JPA and JDBC runtime; intentionally excludes application scanning. */
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaAuditing
    @EnableJpaRepositories(basePackageClasses = PaymentRepository.class)
    @Import({PaymentPersistenceService.class, PaymentStatusEventHandler.class})
    static class TestPersistenceConfig {
        /** Testcontainers-only connection; never references Compose credentials or ports. */
        @Bean
        javax.sql.DataSource dataSource() { return database(); }

        /** Shares the disposable database with real repository transactions. */
        @Bean
        JdbcTemplate jdbcTemplate(javax.sql.DataSource source) { return new JdbcTemplate(source); }

        /** Configures the real payment-core entity mappings without starting Dubbo. */
        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(javax.sql.DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan("com.paymentgateway.core.persistence.entity");
            var adapter = new HibernateJpaVendorAdapter();
            adapter.setDatabasePlatform("org.hibernate.dialect.PostgreSQLDialect");
            factory.setJpaVendorAdapter(adapter);
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate",
                    "hibernate.jdbc.time_zone", "UTC"));
            return factory;
        }

        /** Uses JPA as transaction manager for both repositories and the shared JdbcTemplate. */
        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
    }

    /** DataSource is always bound to Testcontainers' randomly assigned port. */
    private static javax.sql.DataSource database() {
        return new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    /** An object provider with no live Redis publisher, since SSE is not under test. */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<PortalLiveEventPublisher> noPortalPublisher() {
        return mock(ObjectProvider.class);
    }

    /** Returns a temporary port used for both the broker's advertised Proxy port and host mapping. */
    private static int freeLocalPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    /** Creates only the isolated broker topic and consumer group. */
    private static void configureBroker() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        boolean registered = false;
        while (System.nanoTime() < deadline) {
            var result = broker.execInContainer("sh", MQADMIN, "clusterList", "-n", "rmqnamesrv:9876");
            if (result.getExitCode() == 0 && result.getStdout().contains("broker-a")) {
                registered = true;
                break;
            }
            Thread.sleep(1000);
        }
        assertTrue(registered, "Stage-7 broker did not register");
        brokerAdmin("updateTopic", "-n", "rmqnamesrv:9876", "-c", "DefaultCluster", "-t", TOPIC);
        brokerAdmin("updateSubGroup", "-n", "rmqnamesrv:9876", "-c", "DefaultCluster", "-g", GROUP);
    }

    /** Runs mqadmin exclusively inside the disposable broker. */
    private static void brokerAdmin(String... args) throws Exception {
        String[] command = new String[args.length + 2];
        command[0] = "sh";
        command[1] = MQADMIN;
        System.arraycopy(args, 0, command, 2, args.length);
        var result = broker.execInContainer(command);
        assertEquals(0, result.getExitCode(), "mqadmin failed: " + result.getStdout() + result.getStderr());
    }

    /** Reads the persisted payment status, not the mock result. */
    private static String paymentStatus(JdbcTemplate jdbc, UUID id) {
        return jdbc.queryForObject("SELECT status FROM payment WHERE payment_id=?", String.class, id);
    }

    /** Counts the real committed outbox records. */
    private static long eventCount(JdbcTemplate jdbc, UUID id, String type) {
        return count(jdbc, "SELECT count(*) FROM outbox_event WHERE aggregate_id=? AND event_type=?", id, type);
    }

    /** Query against the temporary PostgreSQL database only. */
    private static long count(JdbcTemplate jdbc, String sql, Object... params) {
        Long result = jdbc.queryForObject(sql, Long.class, params);
        return result == null ? 0 : result;
    }

    /** Waits for real asynchronous broker and HTTP results, avoiding fixed sleep assumptions. */
    private static void await(String label, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(80).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(250);
        }
        throw new AssertionError("Timed out waiting for: " + label);
    }

    /** Verifies the actual PGWH1 signature and SHA-256 digest received over HTTP. */
    private static boolean signatureValid(String eventId, String timestamp, String digest,
            String signatureBase64, byte[] body) {
        try {
            if (eventId == null || timestamp == null || digest == null || signatureBase64 == null) return false;
            String expectedDigest = "sha-256=:" + Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(body)) + ":";
            if (!MessageDigest.isEqual(expectedDigest.getBytes(StandardCharsets.US_ASCII),
                    digest.getBytes(StandardCharsets.US_ASCII))) return false;
            String canonical = String.join("\n", "PGWH1", eventId, timestamp, digest);
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(signingKeys.getPublic());
            verifier.update(canonical.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (Exception e) { return false; }
    }

    /** Immutable received and cryptographically validated webhook. */
    private record ReceivedWebhook(UUID eventId, byte[] body) {}
}
