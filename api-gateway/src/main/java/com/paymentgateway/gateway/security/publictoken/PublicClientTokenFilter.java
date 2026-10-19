package com.paymentgateway.gateway.security.publictoken;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.nimbusds.jose.util.JSONObjectUtils;
import com.paymentgateway.gateway.security.ClientRequestCrypto;
import com.paymentgateway.gateway.security.GatewaySecurityHeaders;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Filters requests for public client token processing and security enforcement.
 */
@Component
public class PublicClientTokenFilter implements GlobalFilter, Ordered {
	private static final Logger log = LoggerFactory.getLogger(PublicClientTokenFilter.class);
	private static final Pattern PAYMENT_BY_ID = Pattern.compile("^/api/v1/payments/([0-9a-fA-F-]{36})$");
	private static final Pattern PUBLIC_PAYMENT_STATUS = Pattern
			.compile("^/api/v1/public/payments/([0-9a-fA-F-]{36})/(status|events)$");

	private final PublicPaymentTokenService tokenService;
	private final PublicTokenReplayCacheService replayCache;
	private final PublicTokenSecurityAuditRepository auditRepository;
	private final PublicTokenSecurityProperties properties;

	/**
	 * Creates a new {@code PublicClientTokenFilter} instance.
	 *
	 * @param tokenService    the token service value
	 * @param replayCache     the replay cache value
	 * @param auditRepository the audit repository value
	 * @param properties      the properties value
	 */
	public PublicClientTokenFilter(PublicPaymentTokenService tokenService, PublicTokenReplayCacheService replayCache,
			PublicTokenSecurityAuditRepository auditRepository, PublicTokenSecurityProperties properties) {
		this.tokenService = tokenService;
		this.replayCache = replayCache;
		this.auditRepository = auditRepository;
		this.properties = properties;
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
		 * PortalTokenFilter runs earlier and owns the /api/v1/portal/** route family.
		 * By the time a portal request reaches this filter, browser-supplied internal
		 * headers have already been removed and verified OIDC identity has been
		 * converted into gateway-owned portal headers. Do not strip those trusted
		 * headers here or the upstream portal controller will see missing headers and
		 * respond with HTTP 400.
		 */
		if (path != null && path.startsWith("/api/v1/portal/")) {
			return chain.filter(exchange);
		}

		ServerWebExchange sanitized = stripUntrustedInternalHeaders(exchange);
		String auth = sanitized.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

		if (!properties.isEnabled() || HttpMethod.OPTIONS.equals(sanitized.getRequest().getMethod()) || path == null
				|| !path.startsWith("/api/v1/") || path.startsWith("/api/v1/checkout/")
				|| "/api/v1/providers".equals(path) || auth == null || !auth.startsWith("Bearer ")) {
			return chain.filter(sanitized);
		}

		String token = auth.substring("Bearer ".length()).trim();
		String requiredScope = requiredScope(sanitized);
		if (requiredScope == null) {
			return reject(sanitized, HttpStatus.FORBIDDEN, "PUBLIC_TOKEN_ENDPOINT_NOT_ALLOWED",
					"Public bearer tokens are not permitted for this endpoint", null, false, "ENDPOINT_NOT_ALLOWED");
		}

		final PublicTokenClaims claims;
		try {
			claims = tokenService.validate(token);
		} catch (PublicTokenException e) {
			HttpStatus status = e.code().contains("NOT_CONFIGURED") ? HttpStatus.SERVICE_UNAVAILABLE
					: HttpStatus.UNAUTHORIZED;
			return reject(sanitized, status, e.code(), e.getMessage(), null, false, e.code());
		}

		if (!claims.scopes().contains(requiredScope)) {
			return reject(sanitized, HttpStatus.FORBIDDEN, "PUBLIC_TOKEN_INSUFFICIENT_SCOPE",
					"Public token does not contain the required scope", claims, false, "INSUFFICIENT_SCOPE");
		}
		if (isWriteScope(requiredScope) && !claims.oneTime()) {
			return reject(sanitized, HttpStatus.FORBIDDEN, "PUBLIC_WRITE_TOKEN_MUST_BE_ONE_TIME",
					"Public write operations require a one-time token", claims, false, "WRITE_TOKEN_NOT_ONE_TIME");
		}

		String bindingFailure = validatePathBinding(sanitized, claims, requiredScope);
		if (bindingFailure != null) {
			return reject(sanitized, HttpStatus.FORBIDDEN, "PUBLIC_TOKEN_BINDING_MISMATCH", bindingFailure, claims,
					false, "BINDING_MISMATCH");
		}

		if (requiresBodyBinding(requiredScope)) {
			return readBody(sanitized).flatMap(body -> {
				final String failure;
				try {
					failure = validateBodyBinding(body, claims, requiredScope);
				} catch (IllegalArgumentException error) {
					return reject(sanitized, HttpStatus.BAD_REQUEST, "INVALID_PUBLIC_REQUEST_BODY",
							"Public client request body is not valid JSON", claims, false, "INVALID_REQUEST_BODY");
				}
				if (failure != null) {
					return reject(sanitized, HttpStatus.FORBIDDEN, "PUBLIC_TOKEN_BINDING_MISMATCH", failure, claims,
							false, "BINDING_MISMATCH");
				}
				return accept(sanitized, chain, claims, requiredScope, body);
			});
		}
		return accept(sanitized, chain, claims, requiredScope, null);
	}

	/**
	 * Executes the accept operation.
	 *
	 * @param exchange      the exchange value
	 * @param chain         the chain value
	 * @param claims        the claims value
	 * @param requiredScope the required scope value
	 * @param body          the request or response body
	 * @return a reactive result that completes with the requested value
	 */
	private Mono<Void> accept(ServerWebExchange exchange, GatewayFilterChain chain, PublicTokenClaims claims,
			String requiredScope, byte[] body) {
		Mono<Boolean> reservation = claims.oneTime() && isWriteScope(requiredScope) ? replayCache.reserve(claims)
				: Mono.just(true);
		return reservation.defaultIfEmpty(false).onErrorMap(PublicTokenReplayCacheUnavailableException::new)
				.flatMap(reserved -> {
					if (!Boolean.TRUE.equals(reserved)) {
						return reject(exchange, HttpStatus.CONFLICT, "PUBLIC_TOKEN_REPLAY_DETECTED",
								"This one-time public payment token has already been used", claims, true,
								"JTI_ALREADY_USED");
					}
					ServerWebExchange authenticated = decorateAuthenticatedRequest(exchange, claims, body);
					return recordBestEffort(event(authenticated, claims, false, "ACCEPTED", null))
							.then(chain.filter(authenticated));
				}).onErrorResume(PublicTokenReplayCacheUnavailableException.class, error -> {
					log.error("Public token replay cache failure", error.getCause());
					return reject(exchange, HttpStatus.SERVICE_UNAVAILABLE, "PUBLIC_TOKEN_REPLAY_CACHE_UNAVAILABLE",
							"Public token replay protection is temporarily unavailable", claims, null,
							"REPLAY_CACHE_UNAVAILABLE");
				});
	}

	/**
	 * Executes the required scope operation.
	 *
	 * @param exchange the exchange value
	 * @return the resulting required scope text
	 */
	private static String requiredScope(ServerWebExchange exchange) {
		String path = exchange.getRequest().getURI().getRawPath();
		HttpMethod method = exchange.getRequest().getMethod();
		if (HttpMethod.POST.equals(method) && "/api/v1/payments".equals(path))
			return "payment:create";
		if (HttpMethod.GET.equals(method) && PAYMENT_BY_ID.matcher(path).matches())
			return "payment:read";
		if (HttpMethod.GET.equals(method) && PUBLIC_PAYMENT_STATUS.matcher(path).matches())
			return "payment:status";
		if (HttpMethod.POST.equals(method) && "/api/v1/customer-qr/inspect".equals(path))
			return "customer-qr:inspect";
		if (HttpMethod.POST.equals(method) && "/api/v1/customer-qr/pay".equals(path))
			return "customer-qr:pay";
		return null;
	}

	/**
	 * Executes the requires body binding operation.
	 *
	 * @param scope the scope value
	 * @return {@code true} when the condition is satisfied; otherwise {@code false}
	 */
	private static boolean requiresBodyBinding(String scope) {
		return "payment:create".equals(scope) || "customer-qr:pay".equals(scope);
	}

	/**
	 * Determines whether write scope.
	 *
	 * @param scope the scope value
	 * @return {@code true} when the condition is satisfied; otherwise {@code false}
	 */
	private static boolean isWriteScope(String scope) {
		return !"payment:read".equals(scope) && !"payment:status".equals(scope);
	}

	/**
	 * Validates path binding.
	 *
	 * @param exchange the exchange value
	 * @param claims   the claims value
	 * @param scope    the scope value
	 * @return the resulting validate path binding text
	 */
	private static String validatePathBinding(ServerWebExchange exchange, PublicTokenClaims claims, String scope) {
		String path = exchange.getRequest().getURI().getRawPath();
		if ("payment:read".equals(scope)) {
			Matcher matcher = PAYMENT_BY_ID.matcher(path);
			if (!matcher.matches() || claims.paymentId() == null)
				return "payment:read token is not bound to this payment";
			return claims.paymentId().toString().equalsIgnoreCase(matcher.group(1)) ? null
					: "paymentId does not match token binding";
		}
		if ("payment:status".equals(scope)) {
			Matcher matcher = PUBLIC_PAYMENT_STATUS.matcher(path);
			if (!matcher.matches())
				return "payment:status path is invalid";
			if (claims.paymentId() != null) {
				return claims.paymentId().toString().equalsIgnoreCase(matcher.group(1)) ? null
						: "paymentId does not match token binding";
			}
			return claims.merchantOrderId() == null || claims.merchantOrderId().isBlank()
					? "payment:status token is missing a payment binding"
					: null;
		}
		return null;
	}

	/**
	 * Validates body binding.
	 *
	 * @param body   the request or response body
	 * @param claims the claims value
	 * @param scope  the scope value
	 * @return the resulting validate body binding text
	 */
	private static String validateBodyBinding(byte[] body, PublicTokenClaims claims, String scope) {
		try {
			Map<String, Object> json = JSONObjectUtils.parse(new String(body, StandardCharsets.UTF_8));
			String merchantOrderId = string(json.get("merchantOrderId"));
			Long amountMinor = number(json.get("amountMinor"));
			String currency = string(json.get("currency"));
			String providerCode = string(json.get("providerCode"));
			if (!safeEquals(claims.merchantOrderId(), merchantOrderId))
				return "merchantOrderId does not match token binding";
			if (claims.amountMinor() == null || !claims.amountMinor().equals(amountMinor))
				return "amountMinor does not match token binding";
			if (!equalsIgnoreCase(claims.currency(), currency))
				return "currency does not match token binding";
			if (!equalsIgnoreCase(claims.providerCode(), providerCode))
				return "providerCode does not match token binding";
			if ("customer-qr:pay".equals(scope)) {
				String customerPhone = string(json.get("customerPhone"));
				if (!safeEquals(claims.customerPhone(), customerPhone))
					return "customerPhone does not match token binding";
			}
			return null;
		} catch (Exception e) {
			throw new IllegalArgumentException("Invalid JSON", e);
		}
	}

	/**
	 * Executes the read body operation.
	 *
	 * @param exchange the exchange value
	 * @return a reactive result that completes with the requested value
	 */
	private Mono<byte[]> readBody(ServerWebExchange exchange) {
		return DataBufferUtils.join(exchange.getRequest().getBody(), 1024 * 1024).map(buffer -> {
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
	 * Executes the decorate authenticated request operation.
	 *
	 * @param exchange the exchange value
	 * @param claims   the claims value
	 * @param body     the request or response body
	 * @return the resulting {@code ServerWebExchange} value
	 */
	private ServerWebExchange decorateAuthenticatedRequest(ServerWebExchange exchange, PublicTokenClaims claims,
			byte[] body) {
		ServerHttpRequest base = exchange.getRequest();
		ServerHttpRequest decorated;
		if (body == null) {
			decorated = base.mutate().headers(headers -> applyAuthenticatedHeaders(headers, claims)).build();
		} else {
			decorated = new ServerHttpRequestDecorator(base) {
				/**
				 * Returns request headers after applying the verified public-token identity.
				 *
				 * @return the decorated request headers
				 */
				@Override
				public HttpHeaders getHeaders() {
					HttpHeaders headers = new HttpHeaders();
					headers.putAll(super.getHeaders());
					applyAuthenticatedHeaders(headers, claims);
					headers.remove(HttpHeaders.CONTENT_LENGTH);
					headers.remove(HttpHeaders.TRANSFER_ENCODING);
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
		}
		return exchange.mutate().request(decorated).build();
	}

	/**
	 * Applies authenticated headers.
	 *
	 * @param headers the headers value
	 * @param claims  the claims value
	 */
	private static void applyAuthenticatedHeaders(HttpHeaders headers, PublicTokenClaims claims) {
		GatewaySecurityHeaders.removeInternal(headers);
		headers.remove(HttpHeaders.AUTHORIZATION);
		headers.remove("X-Merchant-Id");
		headers.remove("X-Key-Id");
		headers.remove("X-Timestamp");
		headers.remove("X-Nonce");
		headers.remove("Content-Digest");
		headers.remove("X-Signature");
		headers.set("X-Merchant-Id", claims.merchantId().toString());
		headers.set(GatewaySecurityHeaders.AUTH_MODE, GatewaySecurityHeaders.AUTH_MODE_PUBLIC_TOKEN);
		headers.set(GatewaySecurityHeaders.PUBLIC_TOKEN_SUBJECT,
				claims.subject() == null ? "public-client" : claims.subject());
		headers.set(GatewaySecurityHeaders.PUBLIC_TOKEN_JTI, claims.jti());
		if (claims.paymentId() != null)
			headers.set(GatewaySecurityHeaders.PUBLIC_TOKEN_PAYMENT_ID, claims.paymentId().toString());
		if (claims.merchantOrderId() != null && !claims.merchantOrderId().isBlank()) {
			headers.set(GatewaySecurityHeaders.PUBLIC_TOKEN_MERCHANT_ORDER_ID, claims.merchantOrderId());
		}
	}

	/**
	 * Executes the strip untrusted internal headers operation.
	 *
	 * @param exchange the exchange value
	 * @return the resulting {@code ServerWebExchange} value
	 */
	private static ServerWebExchange stripUntrustedInternalHeaders(ServerWebExchange exchange) {
		ServerHttpRequest request = exchange.getRequest().mutate().headers(GatewaySecurityHeaders::removeInternal)
				.build();
		return exchange.mutate().request(request).build();
	}

	/**
	 * Executes the reject operation.
	 *
	 * @param exchange       the exchange value
	 * @param status         the payment or delivery status
	 * @param code           the provider or status code
	 * @param message        the message value
	 * @param claims         the claims value
	 * @param replayDetected whether replay detected is enabled
	 * @param failureReason  the failure reason value
	 * @return a reactive result that completes with the requested value
	 */
	private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String code, String message,
			PublicTokenClaims claims, Boolean replayDetected, String failureReason) {
		Mono<Void> audit = claims == null ? Mono.empty()
				: recordBestEffort(event(exchange, claims, replayDetected, "REJECTED", failureReason));
		return audit.then(Mono.defer(() -> {
			exchange.getResponse().setStatusCode(status);
			exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
			String correlation = safeJson(exchange.getRequest().getHeaders().getFirst("X-Correlation-Id"));
			String json = "{\"error\":\"" + safeJson(code) + "\",\"message\":\"" + safeJson(message)
					+ "\",\"correlationId\":\"" + correlation + "\"}";
			byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
			exchange.getResponse().getHeaders().setContentLength(bytes.length);
			return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
		}));
	}

	/**
	 * Executes the event operation.
	 *
	 * @param exchange       the exchange value
	 * @param claims         the claims value
	 * @param replayDetected whether replay detected is enabled
	 * @param decision       the decision value
	 * @param failureReason  the failure reason value
	 * @return the resulting {@code PublicTokenSecurityAuditEvent} value
	 */
	private PublicTokenSecurityAuditEvent event(ServerWebExchange exchange, PublicTokenClaims claims,
			Boolean replayDetected, String decision, String failureReason) {
		return new PublicTokenSecurityAuditEvent(UUID.randomUUID(),
				exchange.getRequest().getHeaders().getFirst("X-Correlation-Id"), claims.merchantId(), claims.subject(),
				ClientRequestCrypto.sha256Hex(claims.jti()), String.join(" ", claims.scopes()),
				exchange.getRequest().getMethod().name(), exchange.getRequest().getURI().getRawPath(),
				claims.expiresAt(), claims.oneTime(), replayDetected, decision, failureReason);
	}

	/**
	 * Executes the record best effort operation.
	 *
	 * @param event the event to process
	 * @return a reactive result that completes with the requested value
	 */
	private Mono<Void> recordBestEffort(PublicTokenSecurityAuditEvent event) {
		return auditRepository.record(event).onErrorResume(error -> {
			log.error("Failed to persist public token security audit event {}", event.securityEventId(), error);
			return Mono.empty();
		});
	}

	/**
	 * Executes the string operation.
	 *
	 * @param value the value to process
	 * @return the resulting string text
	 */
	private static String string(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	/**
	 * Executes the number operation.
	 *
	 * @param value the value to process
	 * @return the resulting number value
	 */
	private static Long number(Object value) {
		if (value == null)
			return null;
		if (value instanceof Number n)
			return n.longValue();
		return Long.valueOf(String.valueOf(value));
	}

	/**
	 * Executes the safe equals operation.
	 *
	 * @param a the a value
	 * @param b the b value
	 * @return {@code true} when the condition is satisfied; otherwise {@code false}
	 */
	private static boolean safeEquals(String a, String b) {
		return a != null && a.equals(b);
	}

	/**
	 * Executes the equals ignore case operation.
	 *
	 * @param a the a value
	 * @param b the b value
	 * @return {@code true} when the condition is satisfied; otherwise {@code false}
	 */
	private static boolean equalsIgnoreCase(String a, String b) {
		return a != null && b != null && a.equalsIgnoreCase(b);
	}

	/**
	 * Executes the safe JSON operation.
	 *
	 * @param value the value to process
	 * @return the resulting safe JSON text
	 */
	private static String safeJson(String value) {
		if (value == null)
			return "";
		return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "");
	}

	/**
	 * Signals a public token replay cache unavailable failure.
	 */
	private static final class PublicTokenReplayCacheUnavailableException extends RuntimeException {
		/**
		 * Creates a new {@code PublicTokenReplayCacheUnavailableException} instance.
		 *
		 * @param cause the cause value
		 */
		private PublicTokenReplayCacheUnavailableException(Throwable cause) {
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
		return -100;
	}
}
