package com.paymentgateway.gateway.security.publictoken;

import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds configuration properties for public token security.
 */
@Component
@ConfigurationProperties(prefix = "payment.security.public-token")
public class PublicTokenSecurityProperties {
	private boolean enabled = true;
	private String issuer = "payment-api-gateway";
	private String audience = "payment-public-api";
	private String hmacSecretBase64 = "";
	private String hmacSecretFile = "";
	private long defaultTtlSeconds = 300;
	private long maxTtlSeconds = 1800;
	private long allowedClockSkewSeconds = 30;
	private String replayKeyPrefix = "payment:public-token:jti:v1";
	private Set<String> allowedScopes = new LinkedHashSet<>(
			Set.of("payment:create", "payment:read", "payment:status", "customer-qr:inspect", "customer-qr:pay"));

	/**
	 * Determines whether enabled.
	 *
	 * @return {@code true} when the condition is satisfied; otherwise {@code false}
	 */
	public boolean isEnabled() {
		return enabled;
	}

	/**
	 * Executes the set enabled operation.
	 *
	 * @param enabled whether enabled is enabled
	 */
	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	/**
	 * Retrieves issuer.
	 *
	 * @return the resulting get issuer text
	 */
	public String getIssuer() {
		return issuer;
	}

	/**
	 * Executes the set issuer operation.
	 *
	 * @param issuer the issuer value
	 */
	public void setIssuer(String issuer) {
		this.issuer = issuer;
	}

	/**
	 * Retrieves audience.
	 *
	 * @return the resulting get audience text
	 */
	public String getAudience() {
		return audience;
	}

	/**
	 * Executes the set audience operation.
	 *
	 * @param audience the audience value
	 */
	public void setAudience(String audience) {
		this.audience = audience;
	}

	/**
	 * Retrieves HMAC secret base64.
	 *
	 * @return the resulting get HMAC secret base64 text
	 */
	public String getHmacSecretBase64() {
		return hmacSecretBase64;
	}

	/**
	 * Executes the set HMAC secret base64 operation.
	 *
	 * @param hmacSecretBase64 the HMAC secret base64 value
	 */
	public void setHmacSecretBase64(String hmacSecretBase64) {
		this.hmacSecretBase64 = hmacSecretBase64;
	}

	/**
	 * Retrieves HMAC secret file.
	 *
	 * @return the resulting get HMAC secret file text
	 */
	public String getHmacSecretFile() {
		return hmacSecretFile;
	}

	/**
	 * Executes the set HMAC secret file operation.
	 *
	 * @param hmacSecretFile the HMAC secret file value
	 */
	public void setHmacSecretFile(String hmacSecretFile) {
		this.hmacSecretFile = hmacSecretFile;
	}

	/**
	 * Retrieves default ttl seconds.
	 *
	 * @return the resulting get default ttl seconds value
	 */
	public long getDefaultTtlSeconds() {
		return defaultTtlSeconds;
	}

	/**
	 * Executes the set default ttl seconds operation.
	 *
	 * @param defaultTtlSeconds the default ttl seconds value
	 */
	public void setDefaultTtlSeconds(long defaultTtlSeconds) {
		this.defaultTtlSeconds = defaultTtlSeconds;
	}

	/**
	 * Retrieves max ttl seconds.
	 *
	 * @return the resulting get max ttl seconds value
	 */
	public long getMaxTtlSeconds() {
		return maxTtlSeconds;
	}

	/**
	 * Executes the set max ttl seconds operation.
	 *
	 * @param maxTtlSeconds the max ttl seconds value
	 */
	public void setMaxTtlSeconds(long maxTtlSeconds) {
		this.maxTtlSeconds = maxTtlSeconds;
	}

	/**
	 * Retrieves allowed clock skew seconds.
	 *
	 * @return the resulting get allowed clock skew seconds value
	 */
	public long getAllowedClockSkewSeconds() {
		return allowedClockSkewSeconds;
	}

	/**
	 * Executes the set allowed clock skew seconds operation.
	 *
	 * @param allowedClockSkewSeconds the allowed clock skew seconds value
	 */
	public void setAllowedClockSkewSeconds(long allowedClockSkewSeconds) {
		this.allowedClockSkewSeconds = allowedClockSkewSeconds;
	}

	/**
	 * Retrieves replay key prefix.
	 *
	 * @return the resulting get replay key prefix text
	 */
	public String getReplayKeyPrefix() {
		return replayKeyPrefix;
	}

	/**
	 * Executes the set replay key prefix operation.
	 *
	 * @param replayKeyPrefix the replay key prefix value
	 */
	public void setReplayKeyPrefix(String replayKeyPrefix) {
		this.replayKeyPrefix = replayKeyPrefix;
	}

	/**
	 * Retrieves allowed scopes.
	 *
	 * @return the matching values
	 */
	public Set<String> getAllowedScopes() {
		return allowedScopes;
	}

	/**
	 * Executes the set allowed scopes operation.
	 *
	 * @param allowedScopes the allowed scopes value
	 */
	public void setAllowedScopes(Set<String> allowedScopes) {
		this.allowedScopes = allowedScopes == null ? new LinkedHashSet<>() : new LinkedHashSet<>(allowedScopes);
	}

	/**
	 * Executes the effective ttl operation.
	 *
	 * @param requested the requested value
	 * @return the resulting effective ttl value
	 */
	public long effectiveTtl(long requested) {
		long defaultTtl = Math.max(30, defaultTtlSeconds);
		long maxTtl = Math.max(defaultTtl, maxTtlSeconds);
		long value = requested <= 0 ? defaultTtl : requested;
		return Math.max(30, Math.min(value, maxTtl));
	}
}
