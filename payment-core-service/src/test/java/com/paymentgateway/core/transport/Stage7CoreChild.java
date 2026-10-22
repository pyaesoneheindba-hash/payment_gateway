package com.paymentgateway.core.transport;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

import com.sun.net.httpserver.HttpServer;
import org.flywaydb.core.Flyway;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import jakarta.persistence.EntityManagerFactory;

import com.paymentgateway.common.ProviderOutcome;
import com.paymentgateway.contract.provider.InitiatePaymentCommand;
import com.paymentgateway.contract.provider.InitiatePaymentResult;
import com.paymentgateway.contract.provider.ProviderRpcService;
import com.paymentgateway.core.persistence.repo.PaymentRepository;
import com.paymentgateway.core.service.PaymentPersistenceService;
import com.paymentgateway.core.service.ProviderClientRegistry;

/**
 * Child JVM with the production @DubboService PaymentRpcServiceImpl, real JPA,
 * Flyway and a disposable PostgreSQL database. The only provider is a Mockito
 * local fake, so NO BANK network calls occur. No Nacos or live DB access.
 */
public final class Stage7CoreChild {
    private static final String PROVIDER = "ABANK_APLUS";
    private static final AtomicInteger BANK_CALLS = new AtomicInteger();
    private static UUID merchantId;
    private static DataSource datasource;

    /** Runs the real Core RPC service against a disposable database and local provider.
     * @param args Core Triple port, diagnostic HTTP port, and test merchant identifier
     * @throws Exception when the isolated Core environment cannot start
     */
    public static void main(String[] args) throws Exception {
        if ("false".equalsIgnoreCase(System.getProperty("dubbo.protocol.triple.rest.enabled")))
            throw new IllegalStateException("Production compatibility test forbids REST-disabled JVM");
        if (args.length != 3) throw new IllegalArgumentException("rpcPort metricsPort merchantId required");
        int rpcPort = Integer.parseInt(args[0]);
        int metricsPort = Integer.parseInt(args[1]);
        merchantId = UUID.fromString(args[2]);
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            datasource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(datasource).locations("classpath:db/migration").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(datasource);
            jdbc.update("""
                INSERT INTO merchant(merchant_id,merchant_code,merchant_name,status,environment,default_currency)
                VALUES (?,?,'Stage 7B3 disposable merchant','ACTIVE','UAT','MMK')
                """, merchantId, "S7B3-" + merchantId.toString().substring(0, 12));

            // Diagnostics binds ONLY to localhost; it reports state from the
            // disposable database, not production data.
            HttpServer metrics = HttpServer.create(
                    new InetSocketAddress(InetAddress.getByName("127.0.0.1"), metricsPort), 0);
            metrics.createContext("/stage7/ready", ex -> {
                byte[] body = "ready".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, body.length);
                try (var out = ex.getResponseBody()) { out.write(body); }
            });
            metrics.createContext("/stage7/metrics", ex -> {
                String query = ex.getRequestURI().getRawQuery();
                String order = "";
                if (query != null && query.startsWith("order=")) {
                    order = java.net.URLDecoder.decode(query.substring(6), StandardCharsets.UTF_8);
                }
                try {
                    Integer paymentRows = jdbc.queryForObject(
                        "SELECT count(*) FROM payment WHERE merchant_id=? AND merchant_order_id=?",
                        Integer.class, merchantId, order);
                    Integer outboxRows = jdbc.queryForObject(
                        "SELECT count(*) FROM outbox_event o JOIN payment p ON o.aggregate_id=p.payment_id "
                          + "WHERE p.merchant_id=? AND p.merchant_order_id=?",
                        Integer.class, merchantId, order);
                    String json = "{\"paymentRows\":" + paymentRows + ",\"outboxRows\":"
                            + outboxRows + ",\"bankCalls\":" + BANK_CALLS.get() + "}";
                    byte[] body = json.getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().set("Content-Type", "application/json");
                    ex.sendResponseHeaders(200, body.length);
                    try (var out = ex.getResponseBody()) { out.write(body); }
                } catch (Exception failure) {
                    byte[] body = "diagnostic query failed".getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(500, body.length);
                    try (var out = ex.getResponseBody()) { out.write(body); }
                }
            });
            try (var context = new SpringApplicationBuilder(CoreBoot.class)
                    .web(WebApplicationType.NONE)
                    .properties(
                        "spring.config.name=stage7b3-no-production-config",
                        "spring.application.name=stage7b3-isolated-core",
                        "spring.main.web-application-type=none",
                        "spring.cloud.nacos.discovery.enabled=false",
                        "spring.cloud.nacos.config.enabled=false",
                        "spring.cloud.discovery.enabled=false",
                        "spring.cloud.service-registry.auto-registration.enabled=false",
                        "dubbo.application.name=stage7b3-isolated-core",
                        "dubbo.application.qos-enable=false",
                        "dubbo.registry.address=N/A",
                        "dubbo.registry.use-as-config-center=false",
                        "dubbo.registry.use-as-metadata-center=false",
                        "dubbo.protocol.name=tri",
                        "dubbo.protocol.host=::1",
                        "dubbo.protocol.port=" + rpcPort,
                        "spring.autoconfigure.exclude="
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,"
                        + "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration")
                    .run()) {
                metrics.start();
                try {
                    System.out.println("STAGE7_CORE_READY:" + rpcPort);
                    new CountDownLatch(1).await();
                } finally {
                    metrics.stop(0);
                }
            }
        }
    }

    /** Configures real Core persistence and RPC with disposable test dependencies. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableDubbo(scanBasePackages = "com.paymentgateway.core.service")
    @EnableTransactionManagement
    @EnableJpaAuditing
    @EnableJpaRepositories(basePackageClasses = PaymentRepository.class)
    @Import(PaymentPersistenceService.class)
    static class CoreBoot {
        /** Exposes the disposable PostgreSQL connection.
         * @return test container data source
         */
        @Bean DataSource dataSource() { return datasource; }
        /** Configures JDBC for the same disposable database.
         * @param db test container data source
         * @return JDBC template
         */
        @Bean JdbcTemplate jdbcTemplate(DataSource db) { return new JdbcTemplate(db); }
        /** Creates the real JPA entity manager against the disposable database.
         * @param db test container data source
         * @return JPA entity manager factory bean
         */
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource db) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(db);
            factory.setPackagesToScan("com.paymentgateway.core.persistence.entity");
            var adapter = new HibernateJpaVendorAdapter();
            adapter.setDatabasePlatform("org.hibernate.dialect.PostgreSQLDialect");
            factory.setJpaVendorAdapter(adapter);
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate",
                    "hibernate.jdbc.time_zone", "UTC"));
            return factory;
        }
        /** Configures transaction management for the isolated JPA entity manager.
         * @param emf JPA entity manager factory
         * @return test transaction manager
         */
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
            return new JpaTransactionManager(emf);
        }
        /** Supplies the synthetic provider, ensuring no bank network traffic.
         * @return isolated provider client registry
         */
        @Bean ProviderClientRegistry isolatedProviderRegistry() {
            var provider = mock(ProviderRpcService.class);
            when(provider.initiate(any(InitiatePaymentCommand.class))).thenAnswer(call -> {
                InitiatePaymentCommand cmd = call.getArgument(0);
                BANK_CALLS.incrementAndGet();
                return new InitiatePaymentResult(ProviderOutcome.SUCCESS, PROVIDER,
                        cmd.providerOrderId(), "LOCAL-" + cmd.paymentId(), "LOCAL-REQUEST",
                        "DISPLAY_QR", "LOCAL-QR-" + cmd.merchantOrderId(), null,
                        Map.of("source", "stage7b3"), "PENDING", "Synthetic provider: no bank network");
            });
            var registry = mock(ProviderClientRegistry.class);
            when(registry.requireBase(PROVIDER)).thenReturn(provider);
            return registry;
        }
    }
}
