package com.paymentgateway.gateway.security.publictoken;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Prevents clients from directly invoking gateway-local security controllers.
 * The external /api/v1/security/public-tokens request is routed through Spring
 * Cloud Gateway filters first; an internal forward carries GATEWAY_ROUTE_ATTR.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InternalSecurityEndpointGuard implements WebFilter {
	/**
	 * Executes the filter operation.
	 *
	 * @param exchange the exchange value
	 * @param chain    the chain value
	 * @return a reactive result that completes with the requested value
	 */
	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		String path = exchange.getRequest().getURI().getRawPath();
		if (path != null && path.startsWith("/internal/security/")
				&& exchange.getAttribute(GATEWAY_ROUTE_ATTR) == null) {
			exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
			return exchange.getResponse().setComplete();
		}
		return chain.filter(exchange);
	}
}
