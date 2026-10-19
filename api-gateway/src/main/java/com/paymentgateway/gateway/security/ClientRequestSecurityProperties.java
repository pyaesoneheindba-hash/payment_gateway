package com.paymentgateway.gateway.security;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds configuration properties for client request security.
 */
@Component
@ConfigurationProperties(prefix = "payment.security.client-signature")
public class ClientRequestSecurityProperties {
	private boolean enabled = true;
	private long allowedClockSkewSeconds = 300;
	private long replayTtlSeconds = 600;
	private int maxBodyBytes = 1024 * 1024;
	private String replayKeyPrefix = "payment:replay:v1";
	private List<String> excludedExactPaths = new ArrayList<>(List.of("/api/v1/providers"));

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
	 * Retrieves replay ttl seconds.
	 *
	 * @return the resulting get replay ttl seconds value
	 */
	public long getReplayTtlSeconds() {
		return replayTtlSeconds;
	}

	/**
	 * Executes the set replay ttl seconds operation.
	 *
	 * @param replayTtlSeconds the replay ttl seconds value
	 */
	public void setReplayTtlSeconds(long replayTtlSeconds) {
		this.replayTtlSeconds = replayTtlSeconds;
	}

	/**
	 * Retrieves max body bytes.
	 *
	 * @return the resulting get max body bytes value
	 */
	public int getMaxBodyBytes() {
		return maxBodyBytes;
	}

	/**
	 * Executes the set max body bytes operation.
	 *
	 * @param maxBodyBytes the max body bytes value
	 */
	public void setMaxBodyBytes(int maxBodyBytes) {
		this.maxBodyBytes = maxBodyBytes;
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
	 * Retrieves excluded exact paths.
	 *
	 * @return the matching values
	 */
	public List<String> getExcludedExactPaths() {
		return excludedExactPaths;
	}

	/**
	 * Executes the set excluded exact paths operation.
	 *
	 * @param excludedExactPaths the excluded exact paths value
	 */
	public void setExcludedExactPaths(List<String> excludedExactPaths) {
		this.excludedExactPaths = excludedExactPaths == null ? new ArrayList<>() : new ArrayList<>(excludedExactPaths);
	}

	/**
	 * Executes the effective replay ttl seconds operation.
	 *
	 * @return the resulting effective replay ttl seconds value
	 */
	public long effectiveReplayTtlSeconds() {
		long minimum = Math.max(1, allowedClockSkewSeconds) * 2;
		return Math.max(Math.max(1, replayTtlSeconds), minimum);
	}

	/**
	 * Executes the effective max body bytes operation.
	 *
	 * @return the resulting effective max body bytes value
	 */
	public int effectiveMaxBodyBytes() {
		return Math.max(1024, maxBodyBytes);
	}
}
