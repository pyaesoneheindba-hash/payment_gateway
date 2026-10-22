package com.paymentgateway.transport;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;

import com.paymentgateway.api.checkout.CheckoutSessionService;
import com.paymentgateway.api.web.MerchantPaymentController;
import com.paymentgateway.common.ProviderOutcome;
import com.paymentgateway.contract.payment.PaymentRpcService;
import com.paymentgateway.contract.provider.InitiatePaymentCommand;
import com.paymentgateway.contract.provider.InitiatePaymentResult;
import com.paymentgateway.contract.provider.ProviderRpcService;
import com.paymentgateway.core.persistence.repo.PaymentRepository;
import com.paymentgateway.core.service.PaymentPersistenceService;
import com.paymentgateway.core.service.PaymentRpcServiceImpl;
import com.paymentgateway.core.service.ProviderClientRegistry;
import com.paymentgateway.gateway.security.ClientRequestCrypto;
import com.paymentgateway.gateway.security.ClientRequestSecurityProperties;
import com.paymentgateway.gateway.security.ClientRequestSignatureFilter;
import com.paymentgateway.gateway.security.ClientSecurityAuditEvent;
import com.paymentgateway.gateway.security.ClientSecurityAuditRepository;
import com.paymentgateway.gateway.security.GatewaySecurityHeaders;
import com.paymentgateway.gateway.security.MerchantSigningKey;
import com.paymentgateway.gateway.security.MerchantSigningKeyRepository;
import com.paymentgateway.gateway.security.ReplayCacheService;

import jakarta.persistence.EntityManagerFactory;
import reactor.core.publisher.Mono;

/**
 * Stage 7.3B3 (first creation path):
 * real localhost HTTP -> Spring Cloud Gateway + PGSIG1 filter
 * -> Spring MVC MerchantPaymentController -> real Dubbo Triple TCP
 * -> production PaymentRpcServiceImpl + PaymentPersistenceService
 * -> disposable PostgreSQL + mocked bank ProviderRpcService.
 *
 * Not a deployment-wide E2E: service discovery, real merchant key DB/Redis,
 * the CheckoutSessionService implementation, RocketMQ delivery and real banks
 * are not included. See the README for the precise boundaries.
 */
class Stage7GatewayApiCoreHttpDubboTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final String KEY_ID = "stage7b3-local-key";
    private static final String PROVIDER = "ABANK_APLUS";
    private static final String PATH = "/api/v1/payments";
    private static final KeyPair KEY_PAIR = keyPair();
    private static final Set<String> NONCES = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger BANK_INITIATIONS = new AtomicInteger();

    private static PostgreSQLContainer<?> postgres;
    private static AnnotationConfigApplicationContext corePersistence;
    private static ApplicationConfig dubboApplication;
    private static RegistryConfig noRegistry;
    private static ServiceConfig<PaymentRpcService> exportedCore;
    private static ReferenceConfig<PaymentRpcService> apiReference;
    private static PaymentRpcService remoteCoreClient;
    private static ConfigurableApplicationContext apiContext;
    private static ConfigurableApplicationContext gatewayContext;
    private static CheckoutSessionService checkoutStub;
    private static int apiPort;
    private static int gatewayPort;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();

    @BeforeAll
    static void start() throws Exception {
        try {
            // No Compose network, existing DB or real merchant rows are accessed.
            postgres = new PostgreSQLContainer<>("postgres:16-alpine");
            postgres.start();
            Flyway.configure().dataSource(database()).locations("classpath:db/migration").load().migrate();
            corePersistence = new AnnotationConfigApplicationContext(CorePersistenceBeans.class);
            JdbcTemplate db = corePersistence.getBean(JdbcTemplate.class);
            db.update("""
                    INSERT INTO merchant(merchant_id,merchant_code,merchant_name,status,environment,default_currency)
                    VALUES (?,?,'Stage 7B3 local merchant','ACTIVE','UAT','MMK')
                    """, MERCHANT_ID, "S7B3-" + MERCHANT_ID.toString().substring(0, 12));

            ProviderRpcService mockBank = mock(ProviderRpcService.class);
            ProviderClientRegistry registry = mock(ProviderClientRegistry.class);
            when(registry.requireBase(PROVIDER)).thenReturn(mockBank);
            when(mockBank.initiate(any(InitiatePaymentCommand.class))).thenAnswer(invocation -> {
                InitiatePaymentCommand c = invocation.getArgument(0);
                BANK_INITIATIONS.incrementAndGet();
                return new InitiatePaymentResult(ProviderOutcome.PENDING, PROVIDER,
                        c.providerOrderId(), "LOCAL-" + c.paymentId(), "LOCAL-REQUEST",
                        "DISPLAY_QR", "LOCAL-QR-" + c.merchantOrderId(), null,
                        Map.of("source", "stage7b3"), "PENDING", "Synthetic provider (not a bank)");
            });

            PaymentRpcServiceImpl productionCore = new PaymentRpcServiceImpl(
                    corePersistence.getBean(PaymentPersistenceService.class), registry);

            dubboApplication = new ApplicationConfig("stage7b3-isolated-core");
            noRegistry = new RegistryConfig("N/A");
            int rpcPort = freeLoopbackPort();
            ProtocolConfig protocol = new ProtocolConfig("tri", rpcPort);
            protocol.setHost("127.0.0.1");

            exportedCore = new ServiceConfig<>();
            exportedCore.setApplication(dubboApplication);
            exportedCore.setRegistry(noRegistry);
            exportedCore.setProtocol(protocol);
            exportedCore.setInterface(PaymentRpcService.class);
            exportedCore.setRef(productionCore);
            exportedCore.setGroup("payment");
            exportedCore.setVersion("1.0.0");
            exportedCore.setScope("remote"); // no injvm shortcut
            exportedCore.export();

            apiReference = new ReferenceConfig<>();
            apiReference.setApplication(dubboApplication);
            apiReference.setRegistry(noRegistry);
            apiReference.setInterface(PaymentRpcService.class);
            apiReference.setUrl("tri://127.0.0.1:" + rpcPort);
            apiReference.setProtocol("tri");
            apiReference.setGroup("payment");
            apiReference.setVersion("1.0.0");
            apiReference.setScope("remote");
            apiReference.setTimeout(10_000);
            apiReference.setRetries(0);
            apiReference.setCheck(true);
            remoteCoreClient = apiReference.get();

            // The real MVC payment controller uses the real networked RPC client.
            // Only its checkout-expiry side effect is stubbed in this first slice.
            checkoutStub = mock(CheckoutSessionService.class);
            when(checkoutStub.applyPaymentExpiry(eq(MERCHANT_ID), any(UUID.class), eq("QR_STRING")))
                    .thenAnswer(invocation -> Instant.now().plusSeconds(300));

            apiContext = new SpringApplicationBuilder(IsolatedPaymentApi.class)
                    .web(WebApplicationType.SERVLET)
                    .properties(testProperties())
                    .run();
            apiPort = ((WebServerApplicationContext) apiContext).getWebServer().getPort();
            assertTrue(apiPort > 0);

            gatewayContext = new SpringApplicationBuilder(IsolatedGateway.class)
                    .web(WebApplicationType.REACTIVE)
                    .properties(testProperties())
                    .run();
            gatewayPort = ((WebServerApplicationContext) gatewayContext).getWebServer().getPort();
            assertTrue(gatewayPort > 0);
        } catch (Throwable failed) {
            stop();
            throw failed;
        }
    }

    @AfterAll
    static void stop() {
        if (gatewayContext != null) gatewayContext.close();
        if (apiContext != null) apiContext.close();
        if (apiReference != null) apiReference.destroy();
        if (exportedCore != null) exportedCore.unexport();
        if (corePersistence != null) corePersistence.close();
        if (postgres != null) postgres.stop();
        // Last: the shared Dubbo internals may be required during context close.
        DubboBootstrap.getInstance().destroy();
    }

    @Test
    void validSignedHttpPaymentCrossesGatewayMvcAndDubboIntoRealPostgres() throws Exception {
        int before = BANK_INITIATIONS.get();
        String order = "S7B3-CREATE-" + UUID.randomUUID();
        String idempotency = UUID.randomUUID().toString();
        String request = requestBody(order);
        HttpResponse<String> response = send(signed(request, idempotency, nonce(), false));

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"status\":\"PENDING\""), response.body());
        assertTrue(response.body().contains("LOCAL-QR-" + order), response.body());
        assertEquals(before + 1, BANK_INITIATIONS.get(), "Exactly one mocked provider initiation");

        JdbcTemplate db = corePersistence.getBean(JdbcTemplate.class);
        Integer rows = db.queryForObject(
                "SELECT count(*) FROM payment WHERE merchant_id=? AND merchant_order_id=? AND status='PENDING'",
                Integer.class, MERCHANT_ID, order);
        assertEquals(1, rows);
        Integer outboxRows = db.queryForObject(
                "SELECT count(*) FROM outbox_event o JOIN payment p ON o.aggregate_id=p.payment_id "
                + "WHERE p.merchant_id=? AND p.merchant_order_id=?",
                Integer.class, MERCHANT_ID, order);
        assertTrue(outboxRows != null && outboxRows >= 2, "Real core outbox must be populated");
    }

    @Test
    void distinctNonceSameIdempotencyDoesNotInitiateBankTwice() throws Exception {
        int before = BANK_INITIATIONS.get();
        String order = "S7B3-IDEMPOTENT-" + UUID.randomUUID();
        String idempotency = UUID.randomUUID().toString();
        String body = requestBody(order);

        HttpResponse<String> first = send(signed(body, idempotency, nonce(), false));
        HttpResponse<String> second = send(signed(body, idempotency, nonce(), false));
        assertEquals(200, first.statusCode(), first.body());
        assertEquals(200, second.statusCode(), second.body());
        assertEquals(before + 1, BANK_INITIATIONS.get(), "Replayed payment must not reinitiate mock bank");

        JdbcTemplate db = corePersistence.getBean(JdbcTemplate.class);
        Integer rows = db.queryForObject(
                "SELECT count(*) FROM payment WHERE merchant_id=? AND merchant_order_id=?",
                Integer.class, MERCHANT_ID, order);
        assertEquals(1, rows);
    }

    @Test
    void invalidSignatureNeverReachesPaymentCore() throws Exception {
        String order = "S7B3-REJECTED-" + UUID.randomUUID();
        int before = BANK_INITIATIONS.get();
        HttpResponse<String> response =
                send(signed(requestBody(order), UUID.randomUUID().toString(), nonce(), true));

        assertEquals(401, response.statusCode(), response.body());
        assertTrue(response.body().contains("INVALID_SIGNATURE"));
        assertEquals(before, BANK_INITIATIONS.get());

        Integer rows = corePersistence.getBean(JdbcTemplate.class).queryForObject(
                "SELECT count(*) FROM payment WHERE merchant_id=? AND merchant_order_id=?",
                Integer.class, MERCHANT_ID, order);
        assertEquals(0, rows);
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest signed(String body, String idem, String nonce, boolean badSignature)
            throws Exception {
        String ts = Long.toString(Instant.now().getEpochSecond());
        String digest = ClientRequestCrypto.contentDigest(body.getBytes(StandardCharsets.UTF_8));
        String canonical = ClientRequestCrypto.canonicalize(
                "POST", PATH, "", MERCHANT_ID.toString(), KEY_ID, ts, nonce, idem, digest);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(KEY_PAIR.getPrivate());
        signer.update((badSignature ? canonical + "tampered" : canonical).getBytes(StandardCharsets.UTF_8));

        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + PATH))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("X-Merchant-Id", MERCHANT_ID.toString())
                .header("X-Key-Id", KEY_ID)
                .header("X-Timestamp", ts)
                .header("X-Nonce", nonce)
                .header("Idempotency-Key", idem)
                .header("Content-Digest", digest)
                .header("X-Signature", Base64.getEncoder().encodeToString(signer.sign()))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static String requestBody(String order) {
        return """
                {"merchantOrderId":"%s","amountMinor":1200,"currency":"MMK",
                "description":"Stage 7B3 simulated payment only","providerCode":"ABANK_APLUS",
                "providerOptions":{},"presentationMode":"QR_STRING"}
                """.formatted(order);
    }

    private static String nonce() { return UUID.randomUUID().toString(); }

    private static KeyPair keyPair() {
        try { return KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static int freeLoopbackPort() throws Exception {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            return socket.getLocalPort();
        }
    }

    private static DataSource database() {
        return new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static String[] testProperties() {
        return new String[] {
                "spring.config.name=stage7b3-nonexistent-config",
                "server.address=127.0.0.1",
                "server.port=0",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                "spring.cloud.gateway.server.webflux.discovery.locator.enabled=false",
                "dubbo.enabled=false",
                "dubbo.registry.address=N/A",
                "spring.autoconfigure.exclude=org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration,"
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
                "management.endpoints.enabled-by-default=false"
        };
    }

    /** Real JPA repos and production persistence service, backed by disposable PostgreSQL. */
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaAuditing
    @EnableJpaRepositories(basePackageClasses = PaymentRepository.class)
    @Import(PaymentPersistenceService.class)
    static class CorePersistenceBeans {
        @Bean DataSource dataSource() { return database(); }
        @Bean JdbcTemplate jdbcTemplate(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(ds);
            factory.setPackagesToScan("com.paymentgateway.core.persistence.entity");
            var adapter = new HibernateJpaVendorAdapter();
            adapter.setDatabasePlatform("org.hibernate.dialect.PostgreSQLDialect");
            factory.setJpaVendorAdapter(adapter);
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate",
                    "hibernate.jdbc.time_zone", "UTC"));
            return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
            return new JpaTransactionManager(emf);
        }
    }

    /** Real embedded Spring MVC controller; avoids all production app scanning. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class IsolatedPaymentApi {
        // On this combined test classpath Spring Security also comes from the
        // Gateway module. The standalone API listener trusts only the local
        // test Gateway; avoid a second default login/CSRF challenge.
        @Bean SecurityFilterChain localApiSecurity(HttpSecurity http) throws Exception {
            return http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(rules -> rules.anyRequest().permitAll())
                    .build();
        }

        @Bean
        MerchantPaymentController merchantPayments() {
            MerchantPaymentController controller = new MerchantPaymentController(checkoutStub);
            // Inject the actual TCP Dubbo ReferenceConfig result instead of
            // letting @DubboReference discover the running Nacos registry.
            ReflectionTestUtils.setField(controller, "paymentRpcService", remoteCoreClient);
            return controller;
        }
    }

    /** Real reactive Gateway routing and real production PGSIG1 GlobalFilter. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(ClientRequestSignatureFilter.class)
    static class IsolatedGateway {
        @Bean RouteLocator paymentsRoute(RouteLocatorBuilder builder) {
            return builder.routes()
                    .route("stage7b3-real-api", predicate ->
                            predicate.path(PATH).uri("http://127.0.0.1:" + apiPort))
                    .build();
        }

        @Bean ClientRequestSecurityProperties requestProperties() {
            return new ClientRequestSecurityProperties();
        }

        @Bean MerchantSigningKeyRepository keys() {
            MerchantSigningKeyRepository keys = mock(MerchantSigningKeyRepository.class);
            MerchantSigningKey key = new MerchantSigningKey(
                    KEY_ID, MERCHANT_ID, "ED25519",
                    Base64.getEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded()),
                    "ACTIVE", "ACTIVE", Instant.now().minusSeconds(30), null);
            when(keys.find(eq(MERCHANT_ID), eq(KEY_ID))).thenReturn(Mono.just(key));
            return keys;
        }

        @Bean ReplayCacheService replay() {
            ReplayCacheService replay = mock(ReplayCacheService.class);
            when(replay.reserve(eq(MERCHANT_ID), eq(KEY_ID), any(String.class)))
                    .thenAnswer(call -> Mono.just(
                            NONCES.add(call.getArgument(2, String.class))));
            return replay;
        }

        @Bean ClientSecurityAuditRepository audit() {
            ClientSecurityAuditRepository audit = mock(ClientSecurityAuditRepository.class);
            when(audit.record(any(ClientSecurityAuditEvent.class))).thenReturn(Mono.empty());
            return audit;
        }

        @Bean SecurityWebFilterChain allowTestRequests(ServerHttpSecurity security) {
            return security.csrf(ServerHttpSecurity.CsrfSpec::disable)
                    .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                    .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                    .authorizeExchange(rules -> rules.anyExchange().permitAll()).build();
        }
    }
}
