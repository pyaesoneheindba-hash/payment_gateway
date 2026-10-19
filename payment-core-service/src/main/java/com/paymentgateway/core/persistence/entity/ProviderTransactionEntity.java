package com.paymentgateway.core.persistence.entity;

import java.util.UUID;

import com.paymentgateway.persistence.BaseAuditableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One normalized provider operation/attempt, independent of the bank-specific
 * protocol.
 */
@Entity
@Table(name = "provider_transaction")
public class ProviderTransactionEntity extends BaseAuditableEntity {
	@Id
	@Column(name = "transaction_record_id")
	private UUID transactionRecordId;
	@Column(name = "payment_id", nullable = false)
	private UUID paymentId;
	@Column(name = "attempt_no", nullable = false)
	private int attemptNo;
	@Column(name = "provider_code", nullable = false, length = 64)
	private String providerCode;
	@Column(nullable = false, length = 32)
	private String operation;
	@Column(name = "provider_request_no", length = 200)
	private String providerRequestNo;
	@Column(name = "provider_order_id", length = 64)
	private String providerOrderId;
	@Column(name = "provider_reference_id", length = 200)
	private String providerReferenceId;
	@Column(name = "outcome", nullable = false, length = 32)
	private String outcome;
	@Column(name = "provider_status", length = 64)
	private String providerStatus;
	@Column(name = "provider_transaction_id", length = 200)
	private String providerTransactionId;
	@Column(name = "provider_response_code", length = 64)
	private String providerResponseCode;
	@Column(name = "provider_response_message", length = 1000)
	private String providerResponseMessage;

	/**
	 * Creates a new {@code ProviderTransactionEntity} instance.
	 */
	protected ProviderTransactionEntity() {
	}

	/**
	 * Executes the of operation.
	 *
	 * @param paymentId           the payment identifier
	 * @param attemptNo           the attempt no value
	 * @param providerCode        the payment provider code
	 * @param operation           the operation value
	 * @param requestNo           the request no value
	 * @param providerOrderId     the provider order identifier value
	 * @param providerReferenceId the provider reference identifier value
	 * @param outcome             the outcome value
	 * @param status              the payment or delivery status
	 * @param txId                the tx identifier value
	 * @param responseCode        the response code value
	 * @param message             the message value
	 * @return the resulting {@code ProviderTransactionEntity} value
	 */
	public static ProviderTransactionEntity of(UUID paymentId, int attemptNo, String providerCode, String operation,
			String requestNo, String providerOrderId, String providerReferenceId, String outcome, String status,
			String txId, String responseCode, String message) {
		ProviderTransactionEntity a = new ProviderTransactionEntity();
		a.transactionRecordId = UUID.randomUUID();
		a.paymentId = paymentId;
		a.attemptNo = attemptNo;
		a.providerCode = providerCode;
		a.operation = operation;
		a.providerRequestNo = requestNo;
		a.providerOrderId = providerOrderId;
		a.providerReferenceId = providerReferenceId;
		a.outcome = outcome;
		a.providerStatus = status;
		a.providerTransactionId = txId;
		a.providerResponseCode = responseCode;
		a.providerResponseMessage = message;
		return a;
	}
}
