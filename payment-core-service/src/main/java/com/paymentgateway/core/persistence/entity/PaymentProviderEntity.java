package com.paymentgateway.core.persistence.entity;

import com.paymentgateway.persistence.BaseAuditableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Represents the persisted payment provider state.
 */
@Entity
@Table(name = "payment_provider")
public class PaymentProviderEntity extends BaseAuditableEntity {
	@Id
	@Column(name = "provider_code", length = 64)
	private String providerCode;
	@Column(name = "display_name", nullable = false, length = 128)
	private String displayName;
	@Column(name = "dubbo_group", nullable = false, length = 128)
	private String dubboGroup;
	@Column(name = "service_version", nullable = false, length = 32)
	private String serviceVersion;
	@Column(name = "rpc_timeout_ms", nullable = false)
	private int rpcTimeoutMs;
	@Column(name = "rpc_retries", nullable = false)
	private int rpcRetries;
	@Column(name = "enabled", nullable = false)
	private boolean enabled;
	@Column(name = "capabilities_json", nullable = false, columnDefinition = "TEXT")
	private String capabilitiesJson;

	/**
	 * Creates a new {@code PaymentProviderEntity} instance.
	 */
	protected PaymentProviderEntity() {
	}

	/**
	 * Retrieves provider code.
	 *
	 * @return the resulting get provider code text
	 */
	public String getProviderCode() {
		return providerCode;
	}

	/**
	 * Retrieves display name.
	 *
	 * @return the resulting get display name text
	 */
	public String getDisplayName() {
		return displayName;
	}

	/**
	 * Retrieves Dubbo group.
	 *
	 * @return the resulting get Dubbo group text
	 */
	public String getDubboGroup() {
		return dubboGroup;
	}

	/**
	 * Retrieves service version.
	 *
	 * @return the resulting get service version text
	 */
	public String getServiceVersion() {
		return serviceVersion;
	}

	/**
	 * Retrieves RPC timeout ms.
	 *
	 * @return the resulting get RPC timeout ms value
	 */
	public int getRpcTimeoutMs() {
		return rpcTimeoutMs;
	}

	/**
	 * Retrieves RPC retries.
	 *
	 * @return the resulting get RPC retries value
	 */
	public int getRpcRetries() {
		return rpcRetries;
	}

	/**
	 * Determines whether enabled.
	 *
	 * @return {@code true} when the condition is satisfied; otherwise {@code false}
	 */
	public boolean isEnabled() {
		return enabled;
	}

	/**
	 * Retrieves capabilities JSON.
	 *
	 * @return the resulting get capabilities JSON text
	 */
	public String getCapabilitiesJson() {
		return capabilitiesJson;
	}
}
