package com.paymentgateway.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import com.paymentgateway.gateway.security.ClientRequestCrypto;

/**
 * Stage 7.3B3. Three independent JVM classpaths:
 *  - api-gateway/test-classes        : WebFlux + real PGSIG1
 *  - payment-api-service/test-classes: MVC + real @DubboReference
 *  - payment-core-service/test-classes: production @DubboService + JPA + disposable PG
 *
 * No Docker Compose services, Nacos registry, live bank or merchant webhook.
 * DO NOT run with any Dubbo REST-disabling JVM options.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Stage7MultiJvmPaymentFlowTest {
    private static final String PATH = "/api/v1/payments";
    private static final String KEY_ID = "stage7b3-local-key";
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final KeyPair PAIR = newKeyPair();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6)).build();
    private static final List<Process> CHILDREN = new ArrayList<>();
    private static Path root;
    private static Path logDir;
    private static int gatewayPort;
    private static int metricsPort;
    private static final java.util.Set<Integer> RESERVED_PORTS = new java.util.HashSet<>();

    @BeforeAll
    static void startSeparateProcesses() throws Exception {
        // The old Surefire override affects ONLY its JVM. Child runtimes must
        // NEVER inherit the old REST-off value: no -D flag is passed to them.
        root = Path.of("..").toAbsolutePath().normalize();
        if (!Files.isRegularFile(root.resolve("pom.xml"))) {
            throw new IllegalStateException("Expected Maven working directory stage7-integration-tests, actual="
                    + Path.of("").toAbsolutePath());
        }
        logDir = root.resolve("stage7-integration-tests/target/stage7-3b3-logs/" + UUID.randomUUID());
        Files.createDirectories(logDir);
        int corePort = localPort();
        metricsPort = localPort();
        int apiPort = localPort();
        gatewayPort = localPort();
        try {
            startChild("core", "payment-core-service",
                    "com.paymentgateway.core.transport.Stage7CoreChild",
                    Integer.toString(corePort), Integer.toString(metricsPort), MERCHANT_ID.toString());
            waitForCoreReady(Duration.ofMinutes(3));
            startChild("api", "payment-api-service",
                    "com.paymentgateway.api.transport.Stage7PaymentApiChild",
                    Integer.toString(apiPort), Integer.toString(corePort));
            waitForTcp("api", apiPort, Duration.ofSeconds(75));
            startChild("gateway", "api-gateway",
                    "com.paymentgateway.gateway.transport.Stage7GatewayChild",
                    Integer.toString(gatewayPort), Integer.toString(apiPort), MERCHANT_ID.toString(),
                    Base64.getEncoder().encodeToString(PAIR.getPublic().getEncoded()));
            waitForTcp("gateway", gatewayPort, Duration.ofSeconds(75));
        } catch (Exception error) {
            System.err.println("Stage7B3 child process logs: " + logDir);
            System.err.println(logTail("core") + logTail("api") + logTail("gateway"));
            stop();
            throw error;
        }
    }

    @AfterAll
    static void stop() {
        for (int i = CHILDREN.size() - 1; i >= 0; i--) {
            Process child = CHILDREN.get(i);
            if (child.isAlive()) {
                child.destroy();
                try {
                    if (!child.waitFor(10, TimeUnit.SECONDS)) child.destroyForcibly();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    child.destroyForcibly();
                }
            }
        }
        CHILDREN.clear();
    }

    @Test @Order(1)
    void signedRequestCreatesPaymentThroughAllThreeJvms() throws Exception {
        String order = "S7B3-CREATE-" + UUID.randomUUID();
        int before = state(order).bankCalls;
        String body = requestBody(order);
        HttpResponse<String> response = send(signed(body, UUID.randomUUID().toString(), false));
        assertEquals(200, response.statusCode(), response.body() + logTail("api") + logTail("core"));
        assertTrue(response.body().contains("\"status\":\"PENDING\""), response.body());
        assertTrue(response.body().contains("LOCAL-QR-" + order), response.body());
        State db = state(order);
        assertEquals(1, db.paymentRows, "Must create one row in isolated real PostgreSQL");
        assertTrue(db.outboxRows >= 1, "Real payment-core must write outbox event(s)");
        assertEquals(before + 1, db.bankCalls, "Exactly one MOCK bank initiate");
    }

    @Test @Order(2)
    void distinctNoncesSameIdempotencyOnlyInitiatesOnce() throws Exception {
        String order = "S7B3-IDEMPOTENT-" + UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        String body = requestBody(order);
        int before = state(order).bankCalls;
        HttpResponse<String> first = send(signed(body, key, false));
        HttpResponse<String> second = send(signed(body, key, false));
        assertEquals(200, first.statusCode(), first.body());
        assertEquals(200, second.statusCode(), second.body());
        State state = state(order);
        assertEquals(1, state.paymentRows);
        assertEquals(before + 1, state.bankCalls, "Replay must not call provider a second time");
    }

    @Test @Order(3)
    void badSignatureNeverCreatesPayment() throws Exception {
        String order = "S7B3-REJECT-" + UUID.randomUUID();
        int before = state(order).bankCalls;
        HttpResponse<String> rejected = send(signed(requestBody(order), UUID.randomUUID().toString(), true));
        assertEquals(401, rejected.statusCode(), rejected.body());
        assertTrue(rejected.body().contains("INVALID_SIGNATURE"), rejected.body());
        State db = state(order);
        assertEquals(0, db.paymentRows);
        assertEquals(before, db.bankCalls);
    }

    private static String requestBody(String order) {
        return """
            {"merchantOrderId":"%s","amountMinor":1200,"currency":"MMK",
             "description":"Stage 7B3 mock provider payment only","providerCode":"ABANK_APLUS",
             "providerOptions":{},"presentationMode":"QR_STRING"}
            """.formatted(order);
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest signed(String body, String idempotency, boolean invalid) throws Exception {
        String now = Long.toString(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        String digest = ClientRequestCrypto.contentDigest(body.getBytes(StandardCharsets.UTF_8));
        String canonical = ClientRequestCrypto.canonicalize("POST", PATH, "",
                MERCHANT_ID.toString(), KEY_ID, now, nonce, idempotency, digest);
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(PAIR.getPrivate());
        signature.update((invalid ? canonical + "-invalid" : canonical).getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + PATH))
                .timeout(Duration.ofSeconds(35))
                .header("Content-Type", "application/json")
                .header("X-Merchant-Id", MERCHANT_ID.toString())
                .header("X-Key-Id", KEY_ID)
                .header("X-Timestamp", now)
                .header("X-Nonce", nonce)
                .header("Idempotency-Key", idempotency)
                .header("Content-Digest", digest)
                .header("X-Signature", Base64.getEncoder().encodeToString(signature.sign()))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private record State(int paymentRows, int outboxRows, int bankCalls) { }

    private static State state(String order) throws Exception {
        String encoded = java.net.URLEncoder.encode(order, StandardCharsets.UTF_8);
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + metricsPort + "/stage7/metrics?order=" + encoded))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        String body = response.body();
        return new State(number(body, "paymentRows"), number(body, "outboxRows"), number(body, "bankCalls"));
    }

    private static int number(String json, String property) {
        var matcher = java.util.regex.Pattern.compile("\\\"" + property + "\\\":(\\d+)").matcher(json);
        if (!matcher.find()) throw new IllegalStateException("Missing " + property + " in " + json);
        return Integer.parseInt(matcher.group(1));
    }

    private static void startChild(String name, String module, String clazz, String... args) throws Exception {
        String cp = moduleClasspath(module);
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        List<String> command = new ArrayList<>(List.of(javaExecutable));
        try (Stream<String> paths = Stream.of(cp.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))) {
            paths.filter(p -> p.endsWith(".jar") && Path.of(p).getFileName().toString().startsWith("mockito-core-"))
                    .findFirst().ifPresent(jar -> command.add("-javaagent:" + jar));
        }
        command.addAll(List.of("-cp", cp, clazz));
        command.addAll(List.of(args));
        var pb = new ProcessBuilder(command).directory(root.toFile())
                .redirectErrorStream(true)
                .redirectOutput(logDir.resolve(name + ".log").toFile());
        var env = pb.environment();
        // Block developer/production app endpoints and secrets inherited from
        // host env; fixture starts only with explicit localhost/test DB values.
        env.keySet().removeIf(key -> {
            String upper = key.toUpperCase(java.util.Locale.ROOT);
            return Stream.of("PAYMENT_", "NACOS_", "AYA_", "ABANK_", "MPU_", "ROCKETMQ_",
                    "DUBBO_", "SPRING_", "REDIS_", "MERCHANT_").anyMatch(upper::startsWith);
        });
        Process process = pb.start();
        CHILDREN.add(process);
    }

    private static String moduleClasspath(String module) throws Exception {
        Path mod = root.resolve(module);
        Path mavenClasspath = mod.resolve("target/stage7-3b3.classpath");
        if (!Files.isRegularFile(mavenClasspath)) {
            throw new IllegalStateException("Missing " + mavenClasspath
                    + "; run stage7-3b3/run-stage7-3b3.ps1 first");
        }
        List<String> entries = new ArrayList<>();
        entries.add(mod.resolve("target/test-classes").toString());
        entries.add(mod.resolve("target/classes").toString());
        // In the Maven local repo a Spring Boot executable JAR has BOOT-INF,
        // not classes in the root: explicitly add the source reactor's class dirs.
        String[] projectDeps = switch (module) {
            case "api-gateway" -> new String[]{"payment-common"};
            case "payment-api-service" -> new String[]{"payment-common", "payment-contract", "integration-audit"};
            case "payment-core-service" -> new String[]{"payment-common", "payment-contract",
                    "persistence-common"};
            default -> throw new IllegalArgumentException(module);
        };
        for (String dep : projectDeps) entries.add(root.resolve(dep + "/target/classes").toString());
        entries.add(Files.readString(mavenClasspath).trim());
        return String.join(java.io.File.pathSeparator, entries);
    }

    private static void waitForCoreReady(Duration timeout) throws Exception {
        long until = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < until) {
            if (!CHILDREN.get(0).isAlive()) throw new IllegalStateException("Core JVM exited:" + logTail("core"));
            try {
                HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + metricsPort + "/stage7/ready"))
                    .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) return;
            } catch (Exception ignored) { /* still booting / Docker starting */ }
            Thread.sleep(300);
        }
        throw new IllegalStateException("Core failed readiness timeout " + logTail("core"));
    }

    private static void waitForTcp(String name, int port, Duration timeout) throws Exception {
        long until = System.nanoTime() + timeout.toNanos();
        Process process = CHILDREN.get(CHILDREN.size() - 1);
        while (System.nanoTime() < until) {
            if (!process.isAlive()) throw new IllegalStateException(name + " JVM exited: " + logTail(name));
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress("127.0.0.1", port), 500);
                return;
            } catch (Exception ignored) { Thread.sleep(200); }
        }
        throw new IllegalStateException(name + " failed readiness timeout: " + logTail(name));
    }

    private static String logTail(String name) {
        if (logDir == null) return "";
        Path log = logDir.resolve(name + ".log");
        try {
            if (!Files.isRegularFile(log)) return "";
            List<String> lines = Files.readAllLines(log);
            return "\n------ " + log + " ------\n"
                    + String.join("\n", lines.subList(Math.max(0, lines.size() - 55), lines.size()));
        } catch (Exception ignored) { return "\nLog unavailable: " + log; }
    }

    private static KeyPair newKeyPair() {
        try { return KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    private static int localPort() throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            try (ServerSocket socket = new ServerSocket()) {
                socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
                int port = socket.getLocalPort();
                if (RESERVED_PORTS.add(port)) return port;
            }
        }
        throw new IllegalStateException("Could not allocate distinct local ports");
    }
}
