package com.paymentgateway.core.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.paymentgateway.core.PaymentCoreApplication;
import com.paymentgateway.core.persistence.repo.PaymentRepository;
import com.paymentgateway.core.service.PaymentRpcServiceImpl;
import com.paymentgateway.core.service.ProviderClientRegistry;

/**
 * Stage 7.3B4 candidate: boots the actual production PaymentCoreApplication
 * using an isolated, disposable database and no Dubbo service registry.
 *
 * <p>This is a startup/bean-wiring test, not a bank adapter test. It does not
 * invoke real provider RPCs. The Mockito replacement only prevents the actual
 * provider registry from connecting to a bank or provider adapter.</p>
 */
@SpringBootTest(
        classes = PaymentCoreApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.application.name=stage7b4-real-core-bootstrap",
            "spring.main.web-application-type=none",
            "spring.flyway.enabled=true",
            "spring.flyway.clean-disabled=true",
            "spring.jpa.hibernate.ddl-auto=validate",
            "dubbo.registry.address=N/A",
            "dubbo.registry.check=false",
            "dubbo.registry.use-as-config-center=false",
            "dubbo.registry.use-as-metadata-center=false",
            "dubbo.application.metadata-type=local",
            "dubbo.application.qos-enable=false",
            "dubbo.protocol.name=tri",
            "spring.cloud.nacos.discovery.enabled=false",
            "spring.cloud.nacos.discovery.register-enabled=false",
            "spring.cloud.nacos.config.enabled=false"
        })
@Testcontainers
class Stage7RealCoreBootstrapTest {

    /** Fresh PostgreSQL instance owned by this test class. */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Allocate a local IPv6 loopback port so the test never binds a LAN interface. */
    private static final int DUBBO_PORT = freeIpv6LoopbackPort();

    /** Actual Spring context initialized by the production bootstrap class. */
    @Autowired
    ApplicationContext context;

    /** Actual Flyway component and production migration configuration. */
    @Autowired
    Flyway flyway;

    /** Connection to the isolated Testcontainers PostgreSQL instance. */
    @Autowired
    DataSource dataSource;

    /** Prevents actual provider discovery or requests during startup checks. */
    @MockitoBean
    ProviderClientRegistry providerClientRegistry;

    /**
     * Supplies disposable database and loopback-only Dubbo settings before
     * production application auto-configuration is evaluated.
     *
     * @param properties Spring test property registry
     */
    @DynamicPropertySource
    static void isolatedProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        properties.add("spring.flyway.user", POSTGRES::getUsername);
        properties.add("spring.flyway.password", POSTGRES::getPassword);
        properties.add("dubbo.protocol.host", () -> "::1");
        properties.add("dubbo.protocol.port", () -> DUBBO_PORT);
        properties.add("dubbo.registry.address", () -> "N/A");
        properties.add("dubbo.registry.use-as-config-center", () -> "false");
        properties.add("dubbo.registry.use-as-metadata-center", () -> "false");
        properties.add("dubbo.application.metadata-type", () -> "local");
    }

    /**
     * Confirms the real Core entry point creates the real persistence/RPC
     * beans and applies the production migrations to a disposable database.
     *
     * @throws Exception when isolated database verification fails
     */
    @Test
    void realApplicationBootsAndMigratesDisposableDatabase() throws Exception {
        assertNotNull(context.getBean(PaymentCoreApplication.class));
        assertNotNull(context.getBean(PaymentRpcServiceImpl.class));
        assertNotNull(context.getBean(PaymentRepository.class));
        assertNotNull(flyway);
        assertTrue(flyway.info().applied().length > 0, "Production migrations must run");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM flyway_schema_history")) {
            assertTrue(result.next());
            assertTrue(result.getLong(1) > 0, "Flyway history must exist in disposable PostgreSQL");
        }
        assertEquals("N/A", context.getEnvironment().getProperty("dubbo.registry.address"));
        assertEquals("::1", context.getEnvironment().getProperty("dubbo.protocol.host"));
    }

    /**
     * Confirms a real Dubbo Triple TCP listener accepts local-only connections.
     *
     * @throws Exception if the local Triple endpoint is unavailable
     */
    @Test
    void realApplicationPublishesTripleTcpOnIpv6Loopback() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("::1", DUBBO_PORT), 3000);
            assertTrue(socket.isConnected());
            assertTrue(socket.getInetAddress() instanceof Inet6Address);
        }
    }

    /**
     * Finds an unused IPv6-loopback TCP port, failing instead of selecting a
     * wildcard or a machine LAN address if IPv6 loopback is unavailable.
     *
     * @return an available local-only port number
     */
    private static int freeIpv6LoopbackPort() {
        try (ServerSocket listener = new ServerSocket()) {
            listener.bind(new InetSocketAddress("::1", 0));
            return listener.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException("IPv6 loopback required for isolated Core bootstrap", e);
        }
    }
}
