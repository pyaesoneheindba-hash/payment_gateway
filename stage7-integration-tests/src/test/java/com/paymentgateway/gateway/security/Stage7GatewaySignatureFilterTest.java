package com.paymentgateway.gateway.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Stage 7.3A: tests the real PGSIG1 Gateway GlobalFilter against mock HTTP
 * requests. External key-store, replay-cache and audit persistence are mocked;
 * this DOES NOT start a gateway server, send payments or exercise Dubbo.
 */
class Stage7GatewaySignatureFilterTest {
    private static final UUID MERCHANT_ID = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final String KEY_ID = "stage7-local-test-key";
    private static final String IDEMPOTENCY_KEY = "stage7-filter-payment-1";
    private static final String PATH = "/api/v1/payments";
    private static final String BODY = """
            {"merchantOrderId":"STAGE7-FILTER-001","amountMinor":1000,
             "currency":"MMK","providerCode":"ABANK_APLUS"}
            """;

    private ClientRequestSignatureFilter filter;
    private MerchantSigningKeyRepository signingKeys;
    private ReplayCacheService replayCache;
    private ClientSecurityAuditRepository audits;
    private KeyPair pair;

    @BeforeEach
    void prepare() throws Exception {
        pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        signingKeys = mock(MerchantSigningKeyRepository.class);
        replayCache = mock(ReplayCacheService.class);
        audits = mock(ClientSecurityAuditRepository.class);

        ClientRequestSecurityProperties props = new ClientRequestSecurityProperties();
        props.setEnabled(true);
        filter = new ClientRequestSignatureFilter(props, signingKeys, replayCache, audits);

        MerchantSigningKey validKey = new MerchantSigningKey(
                KEY_ID, MERCHANT_ID, "ED25519",
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                "ACTIVE", "ACTIVE", Instant.EPOCH, null);
        when(signingKeys.find(eq(MERCHANT_ID), eq(KEY_ID))).thenReturn(Mono.just(validKey));
        when(replayCache.reserve(eq(MERCHANT_ID), eq(KEY_ID), anyString()))
                .thenReturn(Mono.just(true));
        when(audits.record(any(ClientSecurityAuditEvent.class))).thenReturn(Mono.empty());
    }

    @Test
    void validSignatureForwardsOneRequestWithOnlyTrustedIdentityHeaders() throws Exception {
        MockServerWebExchange exchange = signedRequest(newNonce(), Instant.now(), false, false);
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        AtomicInteger downstreamCalls = new AtomicInteger();
        GatewayFilterChain chain = e -> {
            downstreamCalls.incrementAndGet();
            forwarded.set(e);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block(Duration.ofSeconds(10));

        assertEquals(1, downstreamCalls.get());
        assertNotNull(forwarded.get());
        HttpHeaders forwardedHeaders = forwarded.get().getRequest().getHeaders();
        assertEquals(GatewaySecurityHeaders.AUTH_MODE_MERCHANT_SIGNATURE,
                forwardedHeaders.getFirst(GatewaySecurityHeaders.AUTH_MODE));
        assertEquals(KEY_ID, forwardedHeaders.getFirst(GatewaySecurityHeaders.VERIFIED_KEY_ID));
        assertEquals(MERCHANT_ID.toString(), forwardedHeaders.getFirst("X-Merchant-Id"));
        assertEquals(IDEMPOTENCY_KEY, forwardedHeaders.getFirst("Idempotency-Key"));
        assertNull(forwardedHeaders.getFirst("X-Signature"));
        assertNull(forwardedHeaders.getFirst("Content-Digest"));
        assertNull(forwardedHeaders.getFirst("X-Key-Id"));
        assertNull(forwardedHeaders.getFirst("X-Nonce"));
        assertNull(forwardedHeaders.getFirst("X-Timestamp"));
        assertNull(forwardedHeaders.getFirst(HttpHeaders.AUTHORIZATION));
        // Check the validated bytes are re-exposed to the downstream service.
        String forwardedBody = DataBufferUtils.join(forwarded.get().getRequest().getBody())
                .map(buffer -> {
                    try {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        return new String(bytes, StandardCharsets.UTF_8);
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                }).block(Duration.ofSeconds(5));
        assertEquals(BODY, forwardedBody);
    }

    @Test
    void invalidSignatureReturns401WithoutCallingDownstream() throws Exception {
        MockServerWebExchange exchange = signedRequest(newNonce(), Instant.now(), true, false);
        AtomicInteger downstream = new AtomicInteger();
        filter.filter(exchange, countedChain(downstream)).block(Duration.ofSeconds(10));

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertEquals(0, downstream.get());
        verify(replayCache, never()).reserve(any(), anyString(), anyString());
    }

    @Test
    void changedBodyReturns400WithoutCallingDownstream() throws Exception {
        MockServerWebExchange exchange = signedRequest(newNonce(), Instant.now(), false, true);
        AtomicInteger downstream = new AtomicInteger();
        filter.filter(exchange, countedChain(downstream)).block(Duration.ofSeconds(10));

        assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
        assertEquals(0, downstream.get());
        verify(signingKeys, never()).find(any(), anyString());
    }

    @Test
    void replayedNonceReturns409AndIsNotForwardedTwice() throws Exception {
        String nonce = newNonce();
        when(replayCache.reserve(eq(MERCHANT_ID), eq(KEY_ID), eq(nonce)))
                .thenReturn(Mono.just(true), Mono.just(false));
        AtomicInteger downstream = new AtomicInteger();

        MockServerWebExchange first = signedRequest(nonce, Instant.now(), false, false);
        filter.filter(first, countedChain(downstream)).block(Duration.ofSeconds(10));
        MockServerWebExchange second = signedRequest(nonce, Instant.now(), false, false);
        filter.filter(second, countedChain(downstream)).block(Duration.ofSeconds(10));

        assertEquals(1, downstream.get());
        assertEquals(HttpStatus.CONFLICT, second.getResponse().getStatusCode());
    }

    @Test
    void expiredTimestampReturns401BeforeKeyLookup() throws Exception {
        MockServerWebExchange exchange = signedRequest(newNonce(), Instant.now().minusSeconds(3600), false, false);
        AtomicInteger downstream = new AtomicInteger();
        filter.filter(exchange, countedChain(downstream)).block(Duration.ofSeconds(10));

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertEquals(0, downstream.get());
        verify(signingKeys, never()).find(any(), anyString());
    }

    @Test
    void missingSignatureHeadersReturn400() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(BODY));
        AtomicInteger downstream = new AtomicInteger();
        filter.filter(exchange, countedChain(downstream)).block(Duration.ofSeconds(10));

        assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
        assertEquals(0, downstream.get());
    }

    @Test
    void unknownKeyReturns401WithoutForwarding() throws Exception {
        when(signingKeys.find(eq(MERCHANT_ID), eq(KEY_ID))).thenReturn(Mono.empty());
        MockServerWebExchange exchange = signedRequest(newNonce(), Instant.now(), false, false);
        AtomicInteger downstream = new AtomicInteger();
        filter.filter(exchange, countedChain(downstream)).block(Duration.ofSeconds(10));

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertEquals(0, downstream.get());
        verify(replayCache, never()).reserve(any(), anyString(), anyString());
    }

    private MockServerWebExchange signedRequest(String nonce, Instant at,
                                                boolean badSignature, boolean changedBody) throws Exception {
        String timestamp = Long.toString(at.getEpochSecond());
        String digest = ClientRequestCrypto.contentDigest(BODY.getBytes(StandardCharsets.UTF_8));
        String canonical = ClientRequestCrypto.canonicalize(
                "POST", PATH, "", MERCHANT_ID.toString(), KEY_ID,
                timestamp, nonce, IDEMPOTENCY_KEY, digest);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(canonical.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getEncoder().encodeToString(signer.sign());
        if (badSignature) {
            signature = Base64.getEncoder().encodeToString(new byte[64]);
        }
        String bodyOnWire = changedBody ? BODY + " " : BODY;
        return MockServerWebExchange.from(MockServerHttpRequest.post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Merchant-Id", MERCHANT_ID.toString())
                .header("X-Key-Id", KEY_ID)
                .header("X-Timestamp", timestamp)
                .header("X-Nonce", nonce)
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("Content-Digest", digest)
                .header("X-Signature", signature)
                // Attempted untrusted metadata must be replaced, not preserved.
                .header(GatewaySecurityHeaders.AUTH_MODE,
                        GatewaySecurityHeaders.AUTH_MODE_MERCHANT_SIGNATURE)
                .header(GatewaySecurityHeaders.VERIFIED_KEY_ID, "untrusted-client-value")
                .header(HttpHeaders.AUTHORIZATION, "Bearer should-not-reach-backend")
                .body(bodyOnWire));
    }

    private static String newNonce() {
        return UUID.randomUUID().toString();
    }

    private static GatewayFilterChain countedChain(AtomicInteger counter) {
        return exchange -> {
            counter.incrementAndGet();
            return Mono.empty();
        };
    }
}
