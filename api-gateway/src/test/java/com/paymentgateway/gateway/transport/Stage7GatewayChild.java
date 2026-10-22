package com.paymentgateway.gateway.transport;

import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.paymentgateway.gateway.security.ClientRequestSecurityProperties;
import com.paymentgateway.gateway.security.ClientRequestSignatureFilter;
import com.paymentgateway.gateway.security.ClientSecurityAuditEvent;
import com.paymentgateway.gateway.security.ClientSecurityAuditRepository;
import com.paymentgateway.gateway.security.MerchantSigningKey;
import com.paymentgateway.gateway.security.MerchantSigningKeyRepository;
import com.paymentgateway.gateway.security.ReplayCacheService;
import reactor.core.publisher.Mono;

/** Separate-JVM production Gateway WebFlux routing and real PGSIG1 filter. */
public final class Stage7GatewayChild {
    private static int apiPort;
    private static UUID merchantId;
    private static String publicKey;
    private static final String KEY_ID = "stage7b3-local-key";
    private static final Set<String> NONCES = ConcurrentHashMap.newKeySet();

    /** Starts the isolated reactive gateway process for local integration testing.
     * @param args gateway port, payment API port, merchant identifier, and test public key
     * @throws Exception if test configuration or startup fails
     */
    public static void main(String[] args) throws Exception {
        if ("false".equalsIgnoreCase(System.getProperty("dubbo.protocol.triple.rest.enabled")))
            throw new IllegalStateException("Production compatibility test forbids REST-disabled JVM");
        if (args.length != 4) throw new IllegalArgumentException("gatewayPort apiPort merchantId base64PublicKey required");
        int gatewayPort = Integer.parseInt(args[0]);
        apiPort = Integer.parseInt(args[1]);
        merchantId = UUID.fromString(args[2]);
        publicKey = args[3];
        // Nacos/Redis/R2DBC are deliberately replaced by local test-only dependencies.
        // Do NOT disable the production Gateway or its WebFlux implementation.
        try (var ignored = new SpringApplicationBuilder(GatewayBoot.class)
                .web(WebApplicationType.REACTIVE)
                .properties(
                    "spring.config.name=stage7b3-no-production-config",
                    "spring.application.name=stage7b3-local-gateway",
                    "server.address=127.0.0.1",
                    "server.port=" + gatewayPort,
                    "spring.cloud.nacos.discovery.enabled=false",
                    "spring.cloud.nacos.config.enabled=false",
                    "spring.cloud.discovery.enabled=false",
                    "spring.cloud.service-registry.auto-registration.enabled=false",
                    "spring.cloud.gateway.server.webflux.discovery.locator.enabled=false",
                    "spring.autoconfigure.exclude=org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration")
                .run()) {
            System.out.println("STAGE7_GATEWAY_READY:" + gatewayPort);
            new java.util.concurrent.CountDownLatch(1).await();
        }
    }

    /** Test-only gateway configuration with real signature checking and local stubs. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(ClientRequestSignatureFilter.class)
    static class GatewayBoot {
        /** Routes test payment calls to the separate local Payment API process.
         * @param builder gateway route builder
         * @return local payment route configuration
         */
        @Bean
        RouteLocator localApiRoute(RouteLocatorBuilder builder) {
            return builder.routes()
                    .route("stage7b3-api", route -> route.path("/api/v1/payments", "/api/v1/payments/**")
                            .uri("http://127.0.0.1:" + apiPort))
                    .build();
        }
        /** Provides request-signing settings for the isolated test.
         * @return signing configuration
         */
        @Bean
        ClientRequestSecurityProperties requestSecurityProperties() {
            return new ClientRequestSecurityProperties();
        }
        /** Supplies the generated test merchant public key without database access.
         * @return in-memory signing-key lookup
         */
        @Bean
        MerchantSigningKeyRepository signingKeys() {
            var keys = mock(MerchantSigningKeyRepository.class);
            var key = new MerchantSigningKey(KEY_ID, merchantId, "ED25519", publicKey,
                    "ACTIVE", "ACTIVE", Instant.now().minusSeconds(60), null);
            when(keys.find(eq(merchantId), eq(KEY_ID))).thenReturn(Mono.just(key));
            return keys;
        }
        /** Stores local test nonces to reject replayed requests.
         * @return test-only replay cache
         */
        @Bean
        ReplayCacheService replayCache() {
            var replay = mock(ReplayCacheService.class);
            when(replay.reserve(eq(merchantId), eq(KEY_ID), any(String.class)))
                    .thenAnswer(call -> Mono.just(NONCES.add(call.getArgument(2, String.class))));
            return replay;
        }
        /** Discards security audit writes in this isolated transport test.
         * @return inert audit repository
         */
        @Bean
        ClientSecurityAuditRepository securityAudit() {
            var audit = mock(ClientSecurityAuditRepository.class);
            when(audit.record(any(ClientSecurityAuditEvent.class))).thenReturn(Mono.empty());
            return audit;
        }
        // Production signature verification is retained. The OIDC chain is outside this slice.
        /** Allows this slice to test signature enforcement without the unrelated OIDC chain.
         * @param security reactive security configuration
         * @return test-only security chain
         */
        @Bean
        SecurityWebFilterChain localSecurity(ServerHttpSecurity security) {
            return security.csrf(ServerHttpSecurity.CsrfSpec::disable)
                    .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                    .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                    .authorizeExchange(rules -> rules.anyExchange().permitAll())
                    .build();
        }
    }
}
