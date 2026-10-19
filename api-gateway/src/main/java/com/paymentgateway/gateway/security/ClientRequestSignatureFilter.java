package com.paymentgateway.gateway.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Filters requests for client request signature processing and security
 * enforcement.
 */
@Component
public class ClientRequestSignatureFilter implements GlobalFilter, Ordered {
	private static final Logger log = LoggerFactory.getLogger(ClientRequestSignatureFilter.class);

	public static final String HEADER_MERCHANT_ID = "X-Merchant-Id";
	public static final String HEADER_KEY_ID = "X-Key-Id";
	public static final String HEADER_TIMESTAMP = "X-Timestamp";
	public static final String HEADER_NONCE = "X-Nonce";
	public static final String HEADER_IDEMPOTENCY_KEY = "Idempotency-Key";
	public static final String HEADER_CONTENT_DIGEST = "Content-Digest";
	public static final String HEADER_SIGNATURE = "X-Signature";
	public static final String HEADER_VERIFIED_KEY = GatewaySecurityHeaders.VERIFIED_KEY_ID;

	private static final Pattern SAFE_KEY_ID = Pattern.compile("[A-Za-z0-9._:-]{1,100}");
	private static final Pattern SAFE_NONCE = Pattern.compile("[A-Za-z0-9._~:-]{16,128}");
	private static final Set<String> SINGLE_VALUE_SECURITY_HEADERS = Set.of(HEADER_MERCHANT_ID, HEADER_KEY_ID,
			HEADER_TIMESTAMP, HEADER_NONCE, HEADER_IDEMPOTENCY_KEY, HEADER_CONTENT_DIGEST, HEADER_SIGNATURE);

	private final ClientRequestSecurityProperties properties;
	private final MerchantSigningKeyRepository signingKeys;
	private final ReplayCacheService replayCache;
	private final ClientSecurityAuditRepository auditRepository;

	/**
	 * Creates a new {@code ClientRequestSignatureFilter} instance.
	 *
	 * @param properties      the properties value
	 * @param signingKeys     the signing keys value
	 * @param replayCache     the replay cache value
	 * @param auditRepository the audit repository value
	 */
	public ClientRequestSignatureFilter(ClientRequestSecurityProperties properties,
			MerchantSigningKeyRepository signingKeys, ReplayCacheService replayCache,
			ClientSecurityAuditRepository auditRepository) {
		this.properties = properties;
		this.signingKeys = signingKeys;
		this.replayCache = replayCache;
		this.auditRepository = auditRepository;
	}

	/**
	 * Executes the filter operation.
	 *
	 * @param exchange the exchange value
	 * @param chain    the chain value
	 * @return a reactive result that completes with the requested value
	 */
	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		String path = exchange.getRequest().getURI().getRawPath();

		/*
		 * PortalTokenFilter runs earlier and owns /api/v1/portal/**. It already removed
		 * browser-supplied internal headers, validated the OIDC token and created the
		 * trusted portal identity headers. Preserve those headers here.
		 */
		if (path != null && path.startsWith("/api/v1/portal/")) {
			return chain.filter(exchange);
		}

		// PublicClientTokenFilter runs first and creates AUTH_MODE only after a valid
		// bearer token.
		// It strips any client-supplied internal auth headers, so this marker is
		// trusted here.
		if (GatewaySecurityHeaders.AUTH_MODE_PUBLIC_TOKEN
				.equals(exchange.getRequest().getHeaders().getFirst(GatewaySecurityHeaders.AUTH_MODE))) {
			return chain.filter(exchange);
		}
		if (!mustVerify(exchange, path)) {
			return chain.filter(removeUntrustedInternalHeaders(exchange));
		}

		HttpHeaders headers = exchange.getRequest().getHeaders();
		String correlationId = headers.getFirst("X-Correlation-Id");
		if (hasDuplicateSecurityHeaders(headers)) {
			return reject(exchange, HttpStatus.BAD_REQUEST, "DUPLICATE_SECURITY_HEADER",
					"Security headers must contain exactly one value", event(exchange, correlationId, null, null, null,
							null, null, null, null, false, false, "REJECTED", "DUPLICATE_SECURITY_HEADER"));
		}
		String merchantHeader = trim(headers.getFirst(HEADER_MERCHANT_ID));
		String keyId = trim(headers.getFirst(HEADER_KEY_ID));
		String timestampHeader = trim(headers.getFirst(HEADER_TIMESTAMP));
		String nonce = trim(headers.getFirst(HEADER_NONCE));
		String idempotencyKey = trim(headers.getFirst(HEADER_IDEMPOTENCY_KEY));
		String contentDigest = trim(headers.getFirst(HEADER_CONTENT_DIGEST));
		String signature = trim(headers.getFirst(HEADER_SIGNATURE));

		if (isBlank(merchantHeader) || isBlank(keyId) || isBlank(timestampHeader) || isBlank(nonce)
				|| isBlank(contentDigest) || isBlank(signature)) {
			return reject(exchange, HttpStatus.BAD_REQUEST, "SECURITY_HEADERS_MISSING",
					"Required request-signature headers are missing", event(exchange, correlationId, null, keyId, null,
							nonce, null, idempotencyKey, null, false, false, "REJECTED", "SECURITY_HEADERS_MISSING"));
		}

		UUID merchantId;
		try {
			merchantId = UUID.fromString(merchantHeader);
		} catch (IllegalArgumentException e) {
			return reject(exchange, HttpStatus.BAD_REQUEST, "INVALID_MERCHANT_ID", "X-Merchant-Id must be a UUID",
					event(exchange, correlationId, null, keyId, null, nonce, null, idempotencyKey, null, false, false,
							"REJECTED", "INVALID_MERCHANT_ID"));
		}

		if (!SAFE_KEY_ID.matcher(keyId).matches()) {
			return reject(exchange, HttpStatus.BAD_REQUEST, "INVALID_KEY_ID", "X-Key-Id format is invalid",
					event(exchange, correlationId, merchantId, keyId, null, nonce, null, idempotencyKey, null, false,
							false, "REJECTED", "INVALID_KEY_ID"));
		}
		if (!SAFE_NONCE.matcher(nonce).matches()) {
			return reject(exchange, HttpStatus.BAD_REQUEST, "INVALID_NONCE", "X-Nonce format is invalid",
					event(exchange, correlationId, merchantId, keyId, null, nonce, null, idempotencyKey, null, false,
							false, "REJECTED", "INVALID_NONCE"));
		}
		if (idempotencyKey != null && idempotencyKey.length() > 128) {
			return reject(exchange, HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "Idempotency-Key is too long",
					event(exchange, correlationId, merchantId, keyId, null, nonce, null, idempotencyKey, null, false,
							false, "REJECTED", "INVALID_IDEMPOTENCY_KEY"));
		}
		if (signature.length() > 256 || contentDigest.length() > 128) {
			return reject(exchange, HttpStatus.BAD_REQUEST, "INVALID_SECURITY_HEADER", "Security header is too long",
					event(exchange, correlationId, merchantId, keyId, null, nonce, null, idempotencyKey, null, false,
							false, "REJECTED", "INVALID_SECURITY_HEADER"));
		}

		long requestTimestamp;
		Instant requestInstant;
		try {
			requestTimestamp = Long.parseLong(timestampHeader);
			requestInstant = Instant.ofEpochSecond(requestTimestamp);
		} catch (RuntimeException e) {
			return reject(exchange, HttpStatus.BAD_REQUEST, "INVALID_TIMESTAMP",
					"X-Timestamp must be Unix epoch seconds", event(exchange, correlationId, merchantId, keyId, null,
							nonce, null, idempotencyKey, null, false, false, "REJECTED", "INVALID_TIMESTAMP"));
		}

		Instant now = Instant.now();
		long skew = Math.max(1, properties.getAllowedClockSkewSeconds());
		if (requestInstant.isBefore(now.minusSeconds(skew)) || requestInstant.isAfter(now.plusSeconds(skew))) {
			return reject(exchange, HttpStatus.UNAUTHORIZED, "SIGNATURE_EXPIRED",
					"Request timestamp is outside the accepted window",
					event(exchange, correlationId, merchantId, keyId, requestTimestamp, nonce, null, idempotencyKey,
							null, false, false, "REJECTED", "SIGNATURE_EXPIRED"));
		}

		return readBody(exchange).onErrorMap(DataBufferLimitException.class, RequestBodyTooLargeException::new)
				.flatMap(body -> verifyBodyAndSignature(exchange, chain, body, correlationId, merchantId, keyId,
						requestTimestamp, timestampHeader, nonce, idempotencyKey, contentDigest, signature, now))
				.onErrorResume(RequestBodyTooLargeException.class,
						e -> reject(exchange, HttpStatus.PAYLOAD_TOO_LARGE, "REQUEST_BODY_TOO_LARGE",
								"Request body exceeds the gateway signature-verification limit",
								event(exchange, correlationId, merchantId, keyId, requestTimestamp, nonce, null,
										idempotencyKey, null, false, false, "REJECTED", "REQUEST_BODY_TOO_LARGE")));
	}

	/**
	 * Verifies body and signature.
	 *
	 * @param exchange         the exchange value
	 * @param chain            the chain value
	 * @param body             the request or response body
	 * @param correlationId    the correlation identifier used for request tracing
	 * @param merchantId       the merchant identifier
	 * @param keyId            the signing key identifier
	 * @param requestTimestamp the request timestamp value
	 * @param timestampHeader  the timestamp header value
	 * @param nonce            the single-use nonce used for replay protection
	 * @param idempotencyKey   the idempotency key used to deduplicate retries
	 * @param contentDigest    the content digest value
	 * @param signature        the signature value to verify
	 * @param now              the now timestamp
	 * @return a reactive result that completes with the requested value
	 */
	private Mono<Void> verifyBodyAndSignature(ServerWebExchange exchange, GatewayFilterChain chain, byte[] body,
			String correlationId, UUID merchantId, String keyId, long requestTimestamp, String timestampHeader,
			String nonce, String idempotencyKey, String contentDigest, String signature, Instant now) {

		String bodyHash = ClientRequestCrypto.sha256Hex(body);
		if (!ClientRequestCrypto.verifyContentDigest(contentDigest, body)) {
			return reject(exchange, HttpStatus.BAD_REQUEST, "CONTENT_DIGEST_MISMATCH",
					"Content-Digest does not match the request body",
					event(exchange, correlationId, merchantId, keyId, requestTimestamp, nonce, bodyHash, idempotencyKey,
							null, false, false, "REJECTED", "CONTENT_DIGEST_MISMATCH"));
		}

		return signingKeys.find(merchantId, keyId).switchIfEmpty(Mono.error(new UnknownSigningKeyException()))
				.onErrorMap(error -> error instanceof UnknownSigningKeyException ? error
						: new KeyStoreUnavailableException(error))
				.flatMap(key -> {
					String keyFailure = validateKey(key, now);
					if (keyFailure != null) {
						return reject(exchange, HttpStatus.UNAUTHORIZED, keyFailure,
								"Merchant signing key is not valid for this request",
								event(exchange, correlationId, merchantId, keyId, requestTimestamp, nonce, bodyHash,
										idempotencyKey, key.algorithm(), false, false, "REJECTED", keyFailure));
					}

					String canonical = ClientRequestCrypto.canonicalize(exchange.getRequest().getMethod().name(),
							exchange.getRequest().getURI().getRawPath(),
							nullToEmpty(exchange.getRequest().getURI().getRawQuery()), merchantId.toString(), keyId,
							timestampHeader, nonce, nullToEmpty(idempotencyKey), contentDigest);

					if (!ClientRequestCrypto.verifyEd25519(key.publicKey(), canonical, signature)) {
						return reject(exchange, HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE",
								"Cryptographic request signature is invalid",
								event(exchange, correlationId, merchantId, keyId, requestTimestamp, nonce, bodyHash,
										idempotencyKey, key.algorithm(), false, false, "REJECTED",
										"INVALID_SIGNATURE"));
					}

					return replayCache.reserve(merchantId, keyId, nonce).defaultIfEmpty(false)
							.onErrorMap(ReplayCacheUnavailableException::new).flatMap(reserved -> {
								if (!Boolean.TRUE.equals(reserved)) {
									return reject(exchange, HttpStatus.CONFLICT, "REQUEST_REPLAY_DETECTED",
											"This signed request nonce has already been used",
											event(exchange, correlationId, merchantId, keyId, requestTimestamp, nonce,
													bodyHash, idempotencyKey, key.algorithm(), true, true, "REJECTED",
													"REQUEST_REPLAY_DETECTED"));
								}

								ClientSecurityAuditEvent accepted = event(exchange, correlationId, merchantId, keyId,
										requestTimestamp, nonce, bodyHash, idempotencyKey, key.algorithm(), true, false,
										"ACCEPTED", null);
								ServerWebExchange verifiedExchange = decorateVerifiedRequest(exchange, body, keyId);
								return recordBestEffort(accepted).then(chain.filter(verifiedExchange));
							});
				})
				.onErrorResume(UnknownSigningKeyException.class,
						error -> reject(exchange, HttpStatus.UNAUTHORIZED, "UNKNOWN_KEY",
								"Merchant signing key was not found",
								event(exchange, correlationId, merchantId, keyId, requestTimestamp, nonce, bodyHash,
										idempotencyKey, null, false, false, "REJECTED", "UNKNOWN_KEY")))
				.onErrorResume(KeyStoreUnavailableException.class, error -> {
					log.error("Signing-key lookup failure for correlationId={}", correlationId, error.getCause());
					return reject(exchange, HttpStatus.SERVICE_UNAVAILABLE, "SECURITY_KEY_STORE_UNAVAILABLE",
							"Merchant signing-key store is temporarily unavailable",
							event(exchange, correlationId, merchantId, keyId, requestTimestamp, nonce, bodyHash,
									idempotencyKey, null, false, null, "ERROR", "SECURITY_KEY_STORE_UNAVAILABLE"));
				}).onErrorResume(ReplayCacheUnavailableException.class, error -> {
					log.error("Replay cache failure for correlationId={}", correlationId, error.getCause());
					return reject(exchange, HttpStatus.SERVICE_UNAVAILABLE, "REPLAY_CACHE_UNAVAILABLE",
							"Replay protection is temporarily unavailable",
							event(exchange, correlationId, merchantId, keyId, requestTimestamp, nonce, bodyHash,
									idempotencyKey, ClientRequestCrypto.ALGORITHM, true, null, "ERROR",
									"REPLAY_CACHE_UNAVAILABLE"));
				});
	}

	/**
	 * Validates key.
	 *
	 * @param key the lookup or signing key
	 * @param now the now timestamp
	 * @return the resulting validate key text
	 */
	private String validateKey(MerchantSigningKey key, Instant now) {
		if (!"ACTIVE".equalsIgnoreCase(key.merchantStatus()))
			return "MERCHANT_NOT_ACTIVE";
		if (!ClientRequestCrypto.ALGORITHM.equalsIgnoreCase(key.algorithm()))
			return "UNSUPPORTED_KEY_ALGORITHM";
		if (!"ACTIVE".equalsIgnoreCase(key.status()))
			return "KEY_NOT_ACTIVE";
		if (key.validFrom() != null && now.isBefore(key.validFrom()))
			return "KEY_NOT_YET_VALID";
		if (key.validUntil() != null && now.isAfter(key.validUntil()))
			return "KEY_EXPIRED";
		return null;
	}

	/**
	 * Executes the read body operation.
	 *
	 * @param exchange the exchange value
	 * @return a reactive result that completes with the requested value
	 */
	private Mono<byte[]> readBody(ServerWebExchange exchange) {
		return DataBufferUtils.join(exchange.getRequest().getBody(), properties.effectiveMaxBodyBytes()).map(buffer -> {
			try {
				byte[] bytes = new byte[buffer.readableByteCount()];
				buffer.read(bytes);
				return bytes;
			} finally {
				DataBufferUtils.release(buffer);
			}
		}).defaultIfEmpty(new byte[0]);
	}

	/**
	 * Executes the decorate verified request operation.
	 *
	 * @param exchange the exchange value
	 * @param body     the request or response body
	 * @param keyId    the signing key identifier
	 * @return the resulting {@code ServerWebExchange} value
	 */
	private ServerWebExchange decorateVerifiedRequest(ServerWebExchange exchange, byte[] body, String keyId) {
		ServerHttpRequest decorated = new ServerHttpRequestDecorator(exchange.getRequest()) {
			/**
			 * Returns request headers after applying trusted gateway authentication
			 * metadata.
			 *
			 * @return the decorated request headers
			 */
			@Override
			public HttpHeaders getHeaders() {
				HttpHeaders headers = new HttpHeaders();
				headers.putAll(super.getHeaders());
				GatewaySecurityHeaders.removeInternal(headers);
				headers.remove(HttpHeaders.AUTHORIZATION);
				// Provider/payment backends need only the verified merchant identity and
				// business Idempotency-Key; cryptographic proof terminates at the gateway.
				headers.remove(HEADER_KEY_ID);
				headers.remove(HEADER_TIMESTAMP);
				headers.remove(HEADER_NONCE);
				headers.remove(HEADER_CONTENT_DIGEST);
				headers.remove(HEADER_SIGNATURE);
				headers.remove(HttpHeaders.CONTENT_LENGTH);
				headers.remove(HttpHeaders.TRANSFER_ENCODING);
				headers.set(GatewaySecurityHeaders.AUTH_MODE, GatewaySecurityHeaders.AUTH_MODE_MERCHANT_SIGNATURE);
				headers.set(GatewaySecurityHeaders.VERIFIED_KEY_ID, keyId);
				headers.setContentLength(body.length);
				return headers;
			}

			/**
			 * Returns the cached request body for downstream processing.
			 *
			 * @return the decorated request body stream
			 */
			@Override
			public Flux<DataBuffer> getBody() {
				return Flux.defer(() -> Flux.just(exchange.getResponse().bufferFactory().wrap(body)));
			}
		};
		return exchange.mutate().request(decorated).build();
	}

	/**
	 * Removes untrusted internal headers.
	 *
	 * @param exchange the exchange value
	 * @return the resulting {@code ServerWebExchange} value
	 */
	private ServerWebExchange removeUntrustedInternalHeaders(ServerWebExchange exchange) {
		ServerHttpRequest request = exchange.getRequest().mutate().headers(GatewaySecurityHeaders::removeInternal)
				.build();
		return exchange.mutate().request(request).build();
	}

	/**
	 * Determines whether duplicate security headers is available.
	 *
	 * @param headers the headers value
	 * @return {@code true} when the condition is satisfied; otherwise {@code false}
	 */
	private static boolean hasDuplicateSecurityHeaders(HttpHeaders headers) {
		for (String name : SINGLE_VALUE_SECURITY_HEADERS) {
			var values = headers.get(name);
			if (values != null && values.size() != 1)
				return true;
		}
		return false;
	}

	/**
	 * Executes the must verify operation.
	 *
	 * @param exchange the exchange value
	 * @param rawPath  the raw path value
	 * @return {@code true} when the condition is satisfied; otherwise {@code false}
	 */
	private boolean mustVerify(ServerWebExchange exchange, String rawPath) {
		if (!properties.isEnabled())
			return false;
		if (HttpMethod.OPTIONS.equals(exchange.getRequest().getMethod()))
			return false;
		if (rawPath == null || !rawPath.startsWith("/api/v1/"))
			return false;
		if (rawPath.startsWith("/api/v1/checkout/") || rawPath.startsWith("/api/v1/portal/"))
			return false;
		return !properties.getExcludedExactPaths().contains(rawPath);
	}

	/**
	 * Executes the event operation.
	 *
	 * @param exchange              the exchange value
	 * @param correlationId         the correlation identifier used for request
	 *                              tracing
	 * @param merchantId            the merchant identifier
	 * @param keyId                 the signing key identifier
	 * @param requestTimestampEpoch the request timestamp epoch value
	 * @param nonce                 the single-use nonce used for replay protection
	 * @param bodyHash              the body hash value
	 * @param idempotencyKey        the idempotency key used to deduplicate retries
	 * @param signatureAlgorithm    the signature algorithm value
	 * @param signatureVerified     whether signature verified is enabled
	 * @param replayDetected        whether replay detected is enabled
	 * @param decision              the decision value
	 * @param failureReason         the failure reason value
	 * @return the resulting {@code ClientSecurityAuditEvent} value
	 */
	private ClientSecurityAuditEvent event(ServerWebExchange exchange, String correlationId, UUID merchantId,
			String keyId, Long requestTimestampEpoch, String nonce, String bodyHash, String idempotencyKey,
			String signatureAlgorithm, Boolean signatureVerified, Boolean replayDetected, String decision,
			String failureReason) {
		return new ClientSecurityAuditEvent(UUID.randomUUID(), correlationId, merchantId, keyId,
				exchange.getRequest().getMethod().name(), exchange.getRequest().getURI().getRawPath(),
				requestTimestampEpoch, nonce == null ? null : ClientRequestCrypto.sha256Hex(nonce), bodyHash,
				idempotencyKey == null ? null : ClientRequestCrypto.sha256Hex(idempotencyKey), signatureAlgorithm,
				signatureVerified, replayDetected, decision, failureReason);
	}

	/**
	 * Executes the reject operation.
	 *
	 * @param exchange   the exchange value
	 * @param status     the payment or delivery status
	 * @param code       the provider or status code
	 * @param message    the message value
	 * @param auditEvent the client security audit event
	 * @return a reactive result that completes with the requested value
	 */
	private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String code, String message,
			ClientSecurityAuditEvent auditEvent) {
		return recordBestEffort(auditEvent).then(Mono.defer(() -> {
			exchange.getResponse().setStatusCode(status);
			exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
			String correlationId = nullToEmpty(exchange.getRequest().getHeaders().getFirst("X-Correlation-Id"));
			String json = "{\"error\":\"" + code + "\",\"message\":\"" + message + "\",\"correlationId\":\""
					+ correlationId + "\"}";
			byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
			exchange.getResponse().getHeaders().setContentLength(bytes.length);
			return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
		}));
	}

	/**
	 * Executes the record best effort operation.
	 *
	 * @param event the event to process
	 * @return a reactive result that completes with the requested value
	 */
	private Mono<Void> recordBestEffort(ClientSecurityAuditEvent event) {
		return auditRepository.record(event).onErrorResume(error -> {
			log.error("Failed to persist client request security audit event {}", event.securityEventId(), error);
			return Mono.empty();
		});
	}

	/**
	 * Determines whether blank.
	 *
	 * @param value the value to process
	 * @return {@code true} when the condition is satisfied; otherwise {@code false}
	 */
	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	/**
	 * Executes the trim operation.
	 *
	 * @param value the value to process
	 * @return the resulting trim text
	 */
	private static String trim(String value) {
		return value == null ? null : value.trim();
	}

	/**
	 * Executes the null to empty operation.
	 *
	 * @param value the value to process
	 * @return the resulting null to empty text
	 */
	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

	/**
	 * Signals a request body too large failure.
	 */
	private static final class RequestBodyTooLargeException extends RuntimeException {
		/**
		 * Creates a new {@code RequestBodyTooLargeException} instance.
		 *
		 * @param cause the cause value
		 */
		private RequestBodyTooLargeException(Throwable cause) {
			super(cause);
		}
	}

	/**
	 * Signals a unknown signing key failure.
	 */
	private static final class UnknownSigningKeyException extends RuntimeException {
	}

	/**
	 * Signals a key store unavailable failure.
	 */
	private static final class KeyStoreUnavailableException extends RuntimeException {
		/**
		 * Creates a new {@code KeyStoreUnavailableException} instance.
		 *
		 * @param cause the cause value
		 */
		private KeyStoreUnavailableException(Throwable cause) {
			super(cause);
		}
	}

	/**
	 * Signals a replay cache unavailable failure.
	 */
	private static final class ReplayCacheUnavailableException extends RuntimeException {
		/**
		 * Creates a new {@code ReplayCacheUnavailableException} instance.
		 *
		 * @param cause the cause value
		 */
		private ReplayCacheUnavailableException(Throwable cause) {
			super(cause);
		}
	}

	/**
	 * Retrieves order.
	 *
	 * @return the resulting get order value
	 */
	@Override
	public int getOrder() {
		return -90;
	}
}
