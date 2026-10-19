package com.paymentgateway.gateway;

import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Filters requests for correlation identifier processing and security
 * enforcement.
 */
@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {
	private static final String HEADER = "X-Correlation-Id";
	private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

	/**
	 * Executes the filter operation.
	 *
	 * @param exchange the exchange value
	 * @param chain    the chain value
	 * @return a reactive result that completes with the requested value
	 */
	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		String id = exchange.getRequest().getHeaders().getFirst(HEADER);
		if (id == null || !SAFE_ID.matcher(id).matches()) {
			id = UUID.randomUUID().toString();
		}
		final String correlationId = id;
		ServerHttpRequest request = exchange.getRequest().mutate().headers(headers -> {
			headers.remove(HEADER);
			headers.set(HEADER, correlationId);
		}).build();
		exchange.getResponse().getHeaders().set(HEADER, correlationId);
		return chain.filter(exchange.mutate().request(request).build());
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
