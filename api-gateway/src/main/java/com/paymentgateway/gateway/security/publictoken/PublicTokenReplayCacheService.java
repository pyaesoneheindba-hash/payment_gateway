package com.paymentgateway.gateway.security.publictoken;

import com.paymentgateway.gateway.security.ClientRequestCrypto;
import java.time.Duration;
import java.time.Instant;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Provides public token replay cache application services.
 */
@Service
public class PublicTokenReplayCacheService {
	private final ReactiveStringRedisTemplate redis;
	private final PublicTokenSecurityProperties properties;

	/**
	 * Creates a new {@code PublicTokenReplayCacheService} instance.
	 *
	 * @param redis      the Redis value
	 * @param properties the properties value
	 */
	public PublicTokenReplayCacheService(ReactiveStringRedisTemplate redis, PublicTokenSecurityProperties properties) {
		this.redis = redis;
		this.properties = properties;
	}

	/**
	 * Executes the reserve operation.
	 *
	 * @param claims the claims value
	 * @return a reactive result that completes with the requested value
	 */
	public Mono<Boolean> reserve(PublicTokenClaims claims) {
		long seconds = Math.max(1, Duration.between(Instant.now(), claims.expiresAt()).getSeconds()
				+ Math.max(0, properties.getAllowedClockSkewSeconds()));
		String key = properties.getReplayKeyPrefix() + ":" + claims.merchantId() + ":"
				+ ClientRequestCrypto.sha256Hex(claims.jti());
		return redis.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(seconds));
	}
}
