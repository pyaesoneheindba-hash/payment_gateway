package com.txnhub.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Provides client-side access to txn hub.
 */
public final class TxnHubClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String baseUrl;
    private final String merchantId;
    private final String keyId;
    private final TxnHubSigner signer;
    private final HttpClient http;

    /**
     * Creates a new {@code TxnHubClient} instance.
     *
     * @param baseUrl the base URL value
     * @param merchantId the merchant identifier
     * @param keyId the signing key identifier
     * @param privateKeyPem the private key PEM value
     */
    public TxnHubClient(String baseUrl, String merchantId, String keyId, Path privateKeyPem) {
        this.baseUrl = stripTrailingSlash(Objects.requireNonNull(baseUrl));
        this.merchantId = UUID.fromString(Objects.requireNonNull(merchantId)).toString();
        this.keyId = Objects.requireNonNull(keyId);
        this.signer = new TxnHubSigner(Objects.requireNonNull(privateKeyPem));
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /**
     * Executes the new idempotency key operation.
     *
     * @return the resulting new idempotency key text
     */
    public static String newIdempotencyKey() { return UUID.randomUUID().toString(); }

    /**
     * Creates payment.
     *
     * @param request the current request or request payload
     * @param idempotencyKey the idempotency key used to deduplicate retries
     * @return the resulting {@code TxnHubResponse} value
     */
    public TxnHubResponse createPayment(Map<String, Object> request, String idempotencyKey) {
        return postJson("/api/v1/payments", request, idempotencyKey);
    }

    /**
     * Retrieves payment.
     *
     * @param paymentId the payment identifier
     * @return the resulting {@code TxnHubResponse} value
     */
    public TxnHubResponse getPayment(UUID paymentId) {
        return request("GET", "/api/v1/payments/" + paymentId, "", new byte[0], null, null);
    }

    /**
     * Reconciles payment.
     *
     * @param paymentId the payment identifier
     * @return the resulting {@code TxnHubResponse} value
     */
    public TxnHubResponse reconcilePayment(UUID paymentId) {
        return request("POST", "/api/v1/payments/" + paymentId + "/reconcile", "", new byte[0], null, null);
    }

    /**
     * Processes the refund for payment.
     *
     * @param paymentId the payment identifier
     * @param request the current request or request payload
     * @param idempotencyKey the idempotency key used to deduplicate retries
     * @return the resulting {@code TxnHubResponse} value
     */
    public TxnHubResponse refundPayment(UUID paymentId, Map<String, Object> request, String idempotencyKey) {
        return postJson("/api/v1/payments/" + paymentId + "/refunds", request, idempotencyKey);
    }

    /**
     * Voids payment.
     *
     * @param paymentId the payment identifier
     * @param providerOptions the provider-specific operation options
     * @param idempotencyKey the idempotency key used to deduplicate retries
     * @return the resulting {@code TxnHubResponse} value
     */
    public TxnHubResponse voidPayment(UUID paymentId, Map<String, Object> providerOptions, String idempotencyKey) {
        return postJson("/api/v1/payments/" + paymentId + "/void",
                Map.of("providerOptions", providerOptions == null ? Map.of() : providerOptions), idempotencyKey);
    }

    /**
     * Executes the post JSON operation.
     *
     * @param rawPath the raw path value
     * @param body the request or response body
     * @param idempotencyKey the idempotency key used to deduplicate retries
     * @return the resulting {@code TxnHubResponse} value
     */
    public TxnHubResponse postJson(String rawPath, Object body, String idempotencyKey) {
        try {
            byte[] bytes = JSON.writeValueAsBytes(body);
            return request("POST", rawPath, "", bytes, idempotencyKey, "application/json");
        }
        catch (TxnHubException e) { throw e; }
        catch (Exception e) { throw new TxnHubException("Could not serialize TXN HUB request", e); }
    }

    /**
     * Executes the request operation.
     *
     * @param method the method value
     * @param rawPath the raw path value
     * @param rawQuery the raw query value
     * @param body the request or response body
     * @param idempotencyKey the idempotency key used to deduplicate retries
     * @param contentType the content type value
     * @return the resulting {@code TxnHubResponse} value
     */
    public TxnHubResponse request(String method, String rawPath, String rawQuery, byte[] body,
                                  String idempotencyKey, String contentType) {
        try {
            String timestamp = Long.toString(Instant.now().getEpochSecond());
            String nonce = UUID.randomUUID().toString();
            String digest = TxnHubSigner.contentDigest(body);
            String canonical = TxnHubSigner.canonical(method, rawPath, rawQuery, merchantId, keyId,
                    timestamp, nonce, idempotencyKey, digest);
            String signature = signer.sign(canonical);
            String url = baseUrl + rawPath + (rawQuery == null || rawQuery.isEmpty() ? "" : "?" + rawQuery);

            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("X-Merchant-Id", merchantId)
                    .header("X-Key-Id", keyId)
                    .header("X-Timestamp", timestamp)
                    .header("X-Nonce", nonce)
                    .header("Content-Digest", digest)
                    .header("X-Signature", signature);
            if (idempotencyKey != null && !idempotencyKey.isBlank()) builder.header("Idempotency-Key", idempotencyKey);
            if (contentType != null) builder.header("Content-Type", contentType);
            builder.method(method.toUpperCase(), body.length == 0
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body));

            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            TxnHubResponse result = new TxnHubResponse(response.statusCode(), response.body(), response.headers());
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new TxnHubException(response.statusCode(), response.body());
            return result;
        }
        catch (TxnHubException e) { throw e; }
        catch (Exception e) { throw new TxnHubException("TXN HUB HTTP request failed", e); }
    }

    /**
     * Executes the strip trailing slash operation.
     *
     * @param value the value to process
     * @return the resulting strip trailing slash text
     */
    private static String stripTrailingSlash(String value) {
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }
}
