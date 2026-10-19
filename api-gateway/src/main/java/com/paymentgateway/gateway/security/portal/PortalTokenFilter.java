package com.paymentgateway.gateway.security.portal;

import com.paymentgateway.gateway.security.GatewaySecurityHeaders;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Converts a verified OIDC identity into gateway-owned authorization headers.
 * The role and merchant binding are loaded from PostgreSQL for every request;
 * browser supplied internal headers are always removed first.
 */
@Component
public class PortalTokenFilter implements GlobalFilter, Ordered {
	private final PortalUserRepository users;

	/**
	 * Creates a new {@code PortalTokenFilter} instance.
	 *
	 * @param users the users value
	 */
	public PortalTokenFilter(PortalUserRepository users) {
		this.users = users;
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
		if (path == null || !path.startsWith("/api/v1/portal/")) {
			return chain.filter(strip(exchange));
		}

		return ReactiveSecurityContextHolder.getContext().map(context -> context.getAuthentication())
				.filter(authentication -> authentication instanceof JwtAuthenticationToken
						&& authentication.isAuthenticated())
				.cast(JwtAuthenticationToken.class).map(Optional::of).defaultIfEmpty(Optional.empty())
				.flatMap(optionalAuthentication -> {
					if (optionalAuthentication.isEmpty()) {
						return reject(exchange, HttpStatus.UNAUTHORIZED, "PORTAL_AUTH_REQUIRED");
					}

					JwtAuthenticationToken authentication = optionalAuthentication.get();
					String subject = authentication.getToken().getSubject();
					if (subject == null || subject.isBlank()) {
						return reject(exchange, HttpStatus.UNAUTHORIZED, "PORTAL_SUBJECT_REQUIRED");
					}

					// Do not place switchIfEmpty() after authorizeAndForward().
					// authorizeAndForward() correctly returns Mono<Void>, whose successful
					// completion
					// has no emitted value and would therefore look "empty" to Reactor. Resolve the
					// repository lookup to an Optional first, then branch explicitly.
					return users.findByOidcSubject(subject).map(Optional::of).defaultIfEmpty(Optional.empty())
							.flatMap(optionalUser -> optionalUser
									.map(user -> authorizeAndForward(exchange, chain, path, user))
									.orElseGet(() -> reject(exchange, HttpStatus.FORBIDDEN,
											"PORTAL_USER_NOT_REGISTERED")));
				});
	}

	/**
	 * Executes the authorize and forward operation.
	 *
	 * @param exchange the exchange value
	 * @param chain    the chain value
	 * @param path     the path value
	 * @param user     the user value
	 * @return a reactive result that completes with the requested value
	 */
	private Mono<Void> authorizeAndForward(ServerWebExchange exchange, GatewayFilterChain chain, String path,
			PortalUserRecord user) {
		if (!"ACTIVE".equals(user.status())) {
			return reject(exchange, HttpStatus.FORBIDDEN, "PORTAL_USER_DISABLED");
		}
		String role = user.role();
		boolean merchantRole = role != null && role.startsWith("MERCHANT_");
		if (path.startsWith("/api/v1/portal/admin/") && merchantRole) {
			return reject(exchange, HttpStatus.FORBIDDEN, "ADMIN_ROLE_REQUIRED");
		}
		if (path.startsWith("/api/v1/portal/merchant/") && (!merchantRole || user.merchantId() == null)) {
			return reject(exchange, HttpStatus.FORBIDDEN, "MERCHANT_ROLE_REQUIRED");
		}
		if (merchantRole && !"ACTIVE".equals(user.merchantStatus())) {
			boolean pendingSelfService = "PENDING".equals(user.merchantStatus())
					&& isPendingMerchantSelfServicePath(path);
			if (!pendingSelfService) {
				return reject(exchange, HttpStatus.FORBIDDEN, "MERCHANT_NOT_ACTIVE");
			}
		}

		ServerHttpRequest request = exchange.getRequest().mutate().headers(headers -> {
			GatewaySecurityHeaders.removeInternal(headers);
			// Portal ownership headers are gateway-owned on this route family. Never trust
			// a browser-supplied merchant id.
			headers.remove("X-Merchant-Id");
			// Upstream portal APIs receive only gateway-owned identity headers, not the raw
			// OIDC bearer token.
			headers.remove(HttpHeaders.AUTHORIZATION);
			headers.set(GatewaySecurityHeaders.AUTH_MODE, GatewaySecurityHeaders.AUTH_MODE_PORTAL);
			headers.set(GatewaySecurityHeaders.PORTAL_ROLE, role);
			headers.set(GatewaySecurityHeaders.PORTAL_USER_ID, user.portalUserId().toString());
			if (user.merchantStatus() != null)
				headers.set(GatewaySecurityHeaders.PORTAL_MERCHANT_STATUS, user.merchantStatus());
			if (user.merchantId() != null)
				headers.set("X-Merchant-Id", user.merchantId().toString());
		}).build();
		return chain.filter(exchange.mutate().request(request).build());
	}

	/**
	 * A PENDING merchant may enter only the narrow self-service bootstrap path
	 * needed to register its first public signing key. Payment operations,
	 * dashboards, transaction data and webhook management remain blocked until an
	 * administrator explicitly activates the merchant.
	 */
	private static boolean isPendingMerchantSelfServicePath(String path) {
		return "/api/v1/portal/me".equals(path) || "/api/v1/portal/merchant/api-keys".equals(path);
	}

	/**
	 * Executes the strip operation.
	 *
	 * @param exchange the exchange value
	 * @return the resulting {@code ServerWebExchange} value
	 */
	private static ServerWebExchange strip(ServerWebExchange exchange) {
		ServerHttpRequest request = exchange.getRequest().mutate().headers(GatewaySecurityHeaders::removePortalInternal)
				.build();
		return exchange.mutate().request(request).build();
	}

	/**
	 * Executes the reject operation.
	 *
	 * @param exchange the exchange value
	 * @param status   the payment or delivery status
	 * @param code     the provider or status code
	 * @return a reactive result that completes with the requested value
	 */
	private static Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String code) {
		exchange.getResponse().setStatusCode(status);
		exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
		byte[] body = ("{\"error\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8);
		return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
	}

	/**
	 * Retrieves order.
	 *
	 * @return the resulting get order value
	 */
	@Override
	public int getOrder() {
		return -130;
	}
}
