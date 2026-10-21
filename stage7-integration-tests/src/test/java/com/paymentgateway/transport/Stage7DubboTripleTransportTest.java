package com.paymentgateway.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.paymentgateway.common.ProviderCapability;
import com.paymentgateway.common.ProviderOutcome;
import com.paymentgateway.contract.provider.EnquirePaymentCommand;
import com.paymentgateway.contract.provider.EnquirePaymentResult;
import com.paymentgateway.contract.provider.InitiatePaymentCommand;
import com.paymentgateway.contract.provider.InitiatePaymentResult;
import com.paymentgateway.contract.provider.ProviderDescriptor;
import com.paymentgateway.contract.provider.ProviderRpcService;

/**
 * Stage 7.3B2: actual Apache Dubbo Triple TCP transport against a disposable
 * local provider. There is no Spring application, Nacos registration, DB,
 * Docker Compose service, or provider bank HTTP client in this test.
 */
class Stage7DubboTripleTransportTest {
    private static final String GROUP = "stage7-local-provider-" + UUID.randomUUID();
    private static final String VERSION = "1.0.0";
    private static final AtomicInteger INITIATE_CALLS = new AtomicInteger();
    private static final AtomicInteger ENQUIRE_CALLS = new AtomicInteger();

    private static int port;
    private static ServiceConfig<ProviderRpcService> exporter;
    private static ReferenceConfig<ProviderRpcService> reference;
    private static ProviderRpcService client;
    private static ApplicationConfig application;
    private static RegistryConfig noRegistry;

    @BeforeAll
    static void startIsolatedTripleEndpoint() throws Exception {
        port = findFreeLoopbackPort();
        application = new ApplicationConfig("stage7-triple-rpc-transport-test");
        noRegistry = new RegistryConfig("N/A");

        ProtocolConfig protocol = new ProtocolConfig("tri", port);
        protocol.setHost("127.0.0.1");

        exporter = new ServiceConfig<>();
        exporter.setApplication(application);
        exporter.setRegistry(noRegistry);
        exporter.setProtocol(protocol);
        exporter.setInterface(ProviderRpcService.class);
        exporter.setRef(new LocalFakeProvider());
        exporter.setGroup(GROUP);
        exporter.setVersion(VERSION);
        // In-process/injvm dispatch is expressly disallowed: test must cross TCP.
        exporter.setScope("remote");
        exporter.export();

        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            assertTrue(probe.isConnected(), "Local Dubbo Triple TCP listener must be reachable");
        }

        reference = newReference(3000);
        client = reference.get();
    }

    @AfterAll
    static void closeIsolatedTripleEndpoint() {
        try {
            if (reference != null) reference.destroy();
        } finally {
            try {
                if (exporter != null) exporter.unexport();
            } finally {
                DubboBootstrap.getInstance().destroy();
            }
        }
    }

    @Test
    void descriptorCrossesActualTripleTransport() {
        ProviderDescriptor descriptor = client.descriptor();
        assertEquals("AYAPAY", descriptor.providerCode());
        assertEquals("Stage 7 Mock Provider (No Bank Network)", descriptor.displayName());
        assertTrue(descriptor.capabilities().contains(ProviderCapability.ENQUIRY));
    }

    @Test
    void initiateCommandAndResultSerializeOverTriple() {
        UUID paymentId = UUID.randomUUID();
        String orderId = "STAGE7-" + paymentId;
        InitiatePaymentCommand command = new InitiatePaymentCommand(
                paymentId, orderId, "PG-" + paymentId, 3250L, "MMK",
                "Local RPC contract verification", Map.of("mode", "QR", "source", "stage7"));

        int before = INITIATE_CALLS.get();
        InitiatePaymentResult result = client.initiate(command);

        assertEquals(before + 1, INITIATE_CALLS.get(), "Real server method must execute exactly once");
        assertEquals(ProviderOutcome.SUCCESS, result.outcome());
        assertEquals("AYAPAY", result.providerCode());
        assertEquals(command.providerOrderId(), result.providerOrderId());
        assertEquals("TEST-" + paymentId, result.providerReferenceId());
        assertEquals("MOCK-QR-" + orderId, result.qr());
        assertEquals("DISPLAY_QR", result.nextAction());
        assertEquals("stage7", result.formFields().get("source"));
    }

    @Test
    void enquiryCommandAndResultSerializeOverTriple() {
        UUID paymentId = UUID.randomUUID();
        String providerOrderId = "PG-" + paymentId;
        int before = ENQUIRE_CALLS.get();

        EnquirePaymentResult result = client.enquire(new EnquirePaymentCommand(
                paymentId, providerOrderId, "TEST-" + paymentId,
                "2026-10-21", "DISPLAY_QR"));

        assertEquals(before + 1, ENQUIRE_CALLS.get());
        assertNotNull(result);
        assertEquals(ProviderOutcome.SUCCESS, result.outcome());
        assertEquals(providerOrderId, result.providerOrderId());
        assertEquals("TEST-" + paymentId, result.providerReferenceId());
        assertEquals("LOCAL-VERIFIED", result.providerStatusDescription());
        assertEquals("Stage 7 Mock", result.institutionName());
    }

    @Test
    void slowProviderHonorsClientRpcTimeoutWithoutRetry() {
        // Use a dedicated reference with a deliberately short timeout.
        // Only the test stub sleeps; no banking system or other network is involved.
        ReferenceConfig<ProviderRpcService> shortReference = newReference(250);
        try {
            ProviderRpcService shortClient = shortReference.get();
            InitiatePaymentCommand slow = new InitiatePaymentCommand(
                    UUID.randomUUID(), "STAGE7-DELAY", "PG-STAGE7-DELAY",
                    100L, "MMK", "Timeout test", Map.of("simulateDelay", "true"));
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> shortClient.initiate(slow),
                    "A provider call exceeding 250ms must fail instead of hanging");
            assertNotNull(failure);
        } finally {
            shortReference.destroy();
        }
    }

    private static ReferenceConfig<ProviderRpcService> newReference(int timeoutMs) {
        ReferenceConfig<ProviderRpcService> config = new ReferenceConfig<>();
        config.setApplication(application);
        config.setRegistry(noRegistry);
        config.setInterface(ProviderRpcService.class);
        // A literal 127.0.0.1 direct URL bypasses any service registry.
        config.setUrl("tri://127.0.0.1:" + port);
        config.setProtocol("tri");
        config.setGroup(GROUP);
        config.setVersion(VERSION);
        config.setScope("remote");
        config.setTimeout(timeoutMs);
        config.setRetries(0);
        config.setCheck(true);
        return config;
    }

    private static int findFreeLoopbackPort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }

    /** Test implementation, intentionally independent of all actual provider adapters. */
    private static final class LocalFakeProvider implements ProviderRpcService {
        @Override
        public ProviderDescriptor descriptor() {
            return new ProviderDescriptor("AYAPAY", "Stage 7 Mock Provider (No Bank Network)",
                    Set.of(ProviderCapability.QR_PAYMENT, ProviderCapability.ENQUIRY));
        }

        @Override
        public InitiatePaymentResult initiate(InitiatePaymentCommand command) {
            INITIATE_CALLS.incrementAndGet();
            if (command.providerOptions() != null
                    && "true".equals(command.providerOptions().get("simulateDelay"))) {
                try {
                    Thread.sleep(Duration.ofSeconds(2));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted simulated delay", interrupted);
                }
            }
            return new InitiatePaymentResult(
                    ProviderOutcome.SUCCESS, "AYAPAY", command.providerOrderId(),
                    "TEST-" + command.paymentId(), "STAGE7-REQUEST",
                    "DISPLAY_QR", "MOCK-QR-" + command.merchantOrderId(), null,
                    Map.of("source", command.providerOptions().getOrDefault("source", "stage7")),
                    "200", "Test-only initiation succeeded");
        }

        @Override
        public EnquirePaymentResult enquire(EnquirePaymentCommand command) {
            ENQUIRE_CALLS.incrementAndGet();
            return new EnquirePaymentResult(
                    ProviderOutcome.SUCCESS, command.providerOrderId(),
                    command.providerReferenceId(), 200, "LOCAL-VERIFIED",
                    "MOCK-TXN", "MOCK-BILL", "STAGE7-E2E", "2026-10-21T09:00:00+06:30",
                    "Stage 7 Mock", "Verified by local fake provider");
        }
    }
}
