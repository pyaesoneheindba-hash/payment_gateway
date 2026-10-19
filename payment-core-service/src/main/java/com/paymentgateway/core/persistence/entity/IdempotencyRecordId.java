package com.paymentgateway.core.persistence.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Implements idempotency record identifier behavior for the payment gateway
 * platform.
 */
@Embeddable
public class IdempotencyRecordId implements Serializable {
	private static final long serialVersionUID = 5738687286306471407L;
	@Column(name = "merchant_id")
	private UUID merchantId;
	@Column(name = "operation", length = 64)
	private String operation;
	@Column(name = "idempotency_key", length = 128)
	private String idempotencyKey;

	/**
	 * Creates a new {@code IdempotencyRecordId} instance.
	 */
	protected IdempotencyRecordId() {
	}

	/**
	 * Creates a new {@code IdempotencyRecordId} instance.
	 *
	 * @param merchantId the merchant identifier
	 * @param operation  the operation value
	 * @param key        the lookup or signing key
	 */
	public IdempotencyRecordId(UUID merchantId, String operation, String key) {
		this.merchantId = merchantId;
		this.operation = operation;
		this.idempotencyKey = key;
	}

	/**
	 * Executes the equals operation.
	 *
	 * @param o the o value
	 * @return {@code true} when the condition is satisfied; otherwise {@code false}
	 */
	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (!(o instanceof IdempotencyRecordId that))
			return false;
		return Objects.equals(merchantId, that.merchantId) && Objects.equals(operation, that.operation)
				&& Objects.equals(idempotencyKey, that.idempotencyKey);
	}

	/**
	 * Computes the hash for code.
	 *
	 * @return the resulting hash code value
	 */
	@Override
	public int hashCode() {
		return Objects.hash(merchantId, operation, idempotencyKey);
	}
}
