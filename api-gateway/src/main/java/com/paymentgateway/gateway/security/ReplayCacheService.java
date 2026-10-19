package com.paymentgateway.gateway.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Provides replay cache application services.
 */
@Service
public class ReplayCacheService {
	private final ReactiveStringRedisTemplate redis;
	private final ClientRequestSecurityProperties properties;

	/**
	 * Creates a new {@code ReplayCacheService} instance.
	 *
	 * @param redis      the Redis value
	 * @param properties the properties value
	 */
	public ReplayCacheService(ReactiveStringRedisTemplate redis, ClientRequestSecurityProperties properties) {
		this.redis = redis;
		this.properties = properties;
	}

	/**
	 * Executes the reserve operation.
	 *
	 * @param merchantId the merchant identifier
	 * @param keyId      the signing key identifier
	 * @param nonce      the single-use nonce used for replay protection
	 * @return a reactive result that completes with the requested value
	 */
	public Mono<Boolean> reserve(UUID merchantId, String keyId, String nonce) {
		String nonceHash = ClientRequestCrypto.sha256Hex(nonce.getBytes(StandardCharsets.UTF_8));
		String key = properties.getReplayKeyPrefix() + ":" + merchantId + ":" + keyId + ":" + nonceHash;
		return redis.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(properties.effectiveReplayTtlSeconds()));
	}
}
