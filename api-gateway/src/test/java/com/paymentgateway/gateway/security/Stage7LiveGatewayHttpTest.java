package com.paymentgateway.gateway.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

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
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * Stage 7.3B1: genuine loopback HTTP, real Spring Cloud Gateway route machinery,
 * and the production PGSIG1 GlobalFilter; all downstream traffic goes to a
 * disposable inert HTTP stub. No payment service, provider, Nacos, or project
 * Docker Compose resources are used.
 */
@SpringBootTest(
        classes = Stage7LiveGatewayHttpTest.GatewayTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.config.name=stage7b-test",
                "spring.main.web-application-type=reactive",
                "server.address=127.0.0.1",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                "spring.cloud.gateway.server.webflux.discovery.locator.enabled=false",
                "spring.autoconfigure.exclude=org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration"
        })
class Stage7LiveGatewayHttpTest {

    private static final String PATH = "/api/v1/payments";
    private static final UUID MERCHANT = UUID.fromString("db27f9cf-f21c-4d7b-8c0c-4c61b553a1e3");
    private static final String KEY_ID = "stage7b-local-key";
    private static final String IDEMPOTENCY = "stage7b-payment-001";
    private static final String BODY = "{\"amountMinor\":2000,\"currency\":\"MMK\"}";

    private static final Queue<DownstreamCall> RECEIVED = new ConcurrentLinkedQueue<>();
    private static final java.util.Set<String> RESERVED_NONCES = ConcurrentHashMap.newKeySet();
    private static final KeyPair PAIR = newKeyPair();

    // The live stub is started before Spring creates the route locator.
    private static final DisposableServer STUB = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .route(routes -> routes.post(PATH, (request, response) ->
                    request.receive().aggregate().asString(StandardCharsets.UTF_8)
                            .flatMap(body -> {
                                RECEIVED.add(new DownstreamCall(
                                        body,
                                        request.requestHeaders().get("X-Merchant-Id"),
                                        request.requestHeaders().get(GatewaySecurityHeaders.AUTH_MODE),
                                        request.requestHeaders().get(GatewaySecurityHeaders.VERIFIED_KEY_ID),
                                        request.requestHeaders().get("X-Signature"),
                                        request.requestHeaders().get("Content-Digest"),
                                        request.requestHeaders().get("Idempotency-Key")));
                                return response.status(202)
                                        .header("Content-Type", "application/json")
                                        .sendString(Mono.just("{\"mock\":\"accepted\"}")).then();
                            })))
            .bindNow(Duration.ofSeconds(15));

    @Autowired private Environment environment;
    @Autowired private MerchantSigningKeyRepository signingKeys;
    @Autowired private ReplayCacheService replayCache;
    @Autowired private ClientSecurityAuditRepository audit;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(ClientRequestSignatureFilter.class)
    static class GatewayTestApplication {

        @Bean
        RouteLocator stage7Route(RouteLocatorBuilder builder) {
            return builder.routes()
                    .route("stage7-local-downstream",
                            predicate -> predicate.path(PATH)
                                    .uri("http://127.0.0.1:" + STUB.port()))
                    .build();
        }

        @Bean
        ClientRequestSecurityProperties signatureProperties() {
            return new ClientRequestSecurityProperties();
        }

        @Bean
        MerchantSigningKeyRepository merchantSigningKeyRepository() {
            return mock(MerchantSigningKeyRepository.class);
        }

        @Bean
        ReplayCacheService replayCacheService() {
            return mock(ReplayCacheService.class);
        }

        @Bean
        ClientSecurityAuditRepository clientSecurityAuditRepository() {
            return mock(ClientSecurityAuditRepository.class);
        }

        // Network test focuses on PGSIG1 GlobalFilter, not OIDC resource-server auth.
        @Bean
        SecurityWebFilterChain securityChain(ServerHttpSecurity security) {
            return security
                    .csrf(ServerHttpSecurity.CsrfSpec::disable)
                    .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                    .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                    .authorizeExchange(rules -> rules.anyExchange().permitAll())
                    .build();
        }
    }

    @BeforeEach
    void setup() {
        RECEIVED.clear();
        RESERVED_NONCES.clear();
        reset(signingKeys, replayCache, audit);
        MerchantSigningKey key = new MerchantSigningKey(
                KEY_ID, MERCHANT, "ED25519",
                Base64.getEncoder().encodeToString(PAIR.getPublic().getEncoded()),
                "ACTIVE", "ACTIVE", Instant.now().minusSeconds(60), null);
        when(signingKeys.find(eq(MERCHANT), eq(KEY_ID))).thenReturn(Mono.just(key));
        when(replayCache.reserve(eq(MERCHANT), eq(KEY_ID), any(String.class)))
                .thenAnswer(call -> Mono.just(
                        RESERVED_NONCES.add(call.getArgument(2, String.class))));
        when(audit.record(any(ClientSecurityAuditEvent.class))).thenReturn(Mono.empty());
    }

    @AfterAll
    static void stopStub() {
        STUB.disposeNow();
    }

    @Test
    void signedRequestReachesStubThroughRealGatewayRoute() throws Exception {
        HttpResponse<String> response = send(signedRequest(BODY, nonce(), now(), false, false));
        assertEquals(202, response.statusCode(), response.body());
        assertEquals(1, RECEIVED.size(), "The real Gateway route must forward exactly once");
        DownstreamCall forwarded = RECEIVED.peek();
        assertEquals(BODY, forwarded.body());
        assertEquals(MERCHANT.toString(), forwarded.merchantId());
        assertEquals(GatewaySecurityHeaders.AUTH_MODE_MERCHANT_SIGNATURE, forwarded.authMode());
        assertEquals(KEY_ID, forwarded.verifiedKeyId());
        assertNull(forwarded.signature(), "Raw signature must not reach the downstream service");
        assertNull(forwarded.digest(), "Raw digest must not reach the downstream service");
        assertEquals(IDEMPOTENCY, forwarded.idempotencyKey());
    }

    @Test
    void invalidSignatureIsRejectedWithoutForwarding() throws Exception {
        HttpResponse<String> response = send(signedRequest(BODY, nonce(), now(), true, false));
        assertEquals(401, response.statusCode(), response.body());
        assertTrue(response.body().contains("INVALID_SIGNATURE"));
        assertTrue(RECEIVED.isEmpty());
    }

    @Test
    void modifiedBodyIsRejectedWithoutForwarding() throws Exception {
        HttpResponse<String> response = send(signedRequest(BODY, nonce(), now(), false, true));
        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("CONTENT_DIGEST_MISMATCH"));
        assertTrue(RECEIVED.isEmpty());
    }

    @Test
    void sameNonceCannotForwardTwice() throws Exception {
        HttpRequest request = signedRequest(BODY, nonce(), now(), false, false);
        assertEquals(202, send(request).statusCode());
        HttpResponse<String> replay = send(request);
        assertEquals(409, replay.statusCode(), replay.body());
        assertTrue(replay.body().contains("REQUEST_REPLAY_DETECTED"));
        assertEquals(1, RECEIVED.size());
    }

    @Test
    void expiredTimestampIsRejectedWithoutForwarding() throws Exception {
        HttpResponse<String> response =
                send(signedRequest(BODY, nonce(), Long.toString(Instant.now().minusSeconds(1000).getEpochSecond()),
                        false, false));
        assertEquals(401, response.statusCode(), response.body());
        assertTrue(response.body().contains("SIGNATURE_EXPIRED"));
        assertTrue(RECEIVED.isEmpty());
    }

    @Test
    void unsignedRequestIsRejectedWithoutForwarding() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri())
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(BODY))
                .build();
        HttpResponse<String> response = send(request);
        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("SECURITY_HEADERS_MISSING"));
        assertTrue(RECEIVED.isEmpty());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest signedRequest(String body, String nonce, String timestamp,
                                      boolean badSignature, boolean badDigest) throws Exception {
        String digest = ClientRequestCrypto.contentDigest(
                (badDigest ? "altered" : body).getBytes(StandardCharsets.UTF_8));
        String canonical = ClientRequestCrypto.canonicalize("POST", PATH, "",
                MERCHANT.toString(), KEY_ID, timestamp, nonce, IDEMPOTENCY, digest);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(PAIR.getPrivate());
        signer.update((badSignature ? canonical + "-tampered" : canonical).getBytes(StandardCharsets.UTF_8));
        String encoded = Base64.getEncoder().encodeToString(signer.sign());
        return HttpRequest.newBuilder(uri())
                .timeout(Duration.ofSeconds(15))
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .header("X-Merchant-Id", MERCHANT.toString())
                .header("X-Key-Id", KEY_ID)
                .header("X-Timestamp", timestamp)
                .header("X-Nonce", nonce)
                .header("Idempotency-Key", IDEMPOTENCY)
                .header("Content-Digest", digest)
                .header("X-Signature", encoded)
                // Spoofed internal metadata must be overwritten by the real filter.
                .header(GatewaySecurityHeaders.VERIFIED_KEY_ID, "attacker-controlled")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private URI uri() {
        String port = environment.getRequiredProperty("local.server.port");
        return URI.create("http://127.0.0.1:" + port + PATH);
    }

    private static String nonce() {
        return UUID.randomUUID().toString();
    }

    private static String now() {
        return Long.toString(Instant.now().getEpochSecond());
    }

    private static KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception failure) {
            throw new IllegalStateException("Ed25519 unavailable", failure);
        }
    }

    private record DownstreamCall(String body, String merchantId, String authMode,
                                  String verifiedKeyId, String signature, String digest,
                                  String idempotencyKey) {}
}
