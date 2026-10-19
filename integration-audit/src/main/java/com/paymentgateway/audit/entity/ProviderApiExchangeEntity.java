package com.paymentgateway.audit.entity;

import java.time.Instant;
import java.util.UUID;

import com.paymentgateway.persistence.BaseAuditableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Represents the persisted provider API exchange state.
 */
@Entity
@Table(name = "provider_api_exchange")
public class ProviderApiExchangeEntity extends BaseAuditableEntity {
	@Id
	@Column(name = "exchange_id", nullable = false)
	private UUID exchangeId;
	@Column(name = "payment_id")
	private UUID paymentId;
	@Column(name = "provider_code", nullable = false, length = 64)
	private String providerCode;
	@Column(name = "operation", nullable = false, length = 64)
	private String operation;
	@Column(name = "provider_request_no", length = 200)
	private String providerRequestNo;
	@Column(name = "provider_order_id", length = 64)
	private String providerOrderId;
	@Column(name = "provider_reference_id", length = 200)
	private String providerReferenceId;
	@Column(name = "http_method", nullable = false, length = 16)
	private String httpMethod;
	@Column(name = "request_url", nullable = false, length = 2000)
	private String requestUrl;
	@Column(name = "request_headers", columnDefinition = "TEXT")
	private String requestHeaders;
	@Column(name = "request_body", columnDefinition = "TEXT")
	private String requestBody;
	@Column(name = "response_http_status")
	private Integer responseHttpStatus;
	@Column(name = "response_headers", columnDefinition = "TEXT")
	private String responseHeaders;
	@Column(name = "response_body", columnDefinition = "TEXT")
	private String responseBody;
	@Column(name = "provider_response_code", length = 64)
	private String providerResponseCode;
	@Column(name = "provider_response_message", length = 1000)
	private String providerResponseMessage;
	@Column(name = "outcome", length = 32)
	private String outcome;
	@Column(name = "error_class", length = 255)
	private String errorClass;
	@Column(name = "error_message", columnDefinition = "TEXT")
	private String errorMessage;
	@Column(name = "started_at", nullable = false)
	private Instant startedAt;
	@Column(name = "completed_at")
	private Instant completedAt;
	@Column(name = "duration_ms")
	private Long durationMs;

	/**
	 * Creates a new {@code ProviderApiExchangeEntity} instance.
	 */
	protected ProviderApiExchangeEntity() {
	}

	/**
	 * Executes the started operation.
	 *
	 * @param paymentId         the payment identifier
	 * @param providerCode      the payment provider code
	 * @param operation         the operation value
	 * @param providerRequestNo the provider request no value
	 * @param providerOrderId   the provider order identifier value
	 * @param method            the method value
	 * @param url               the URL value
	 * @param requestHeaders    the request headers value
	 * @param requestBody       the request body value
	 * @return the resulting {@code ProviderApiExchangeEntity} value
	 */
	public static ProviderApiExchangeEntity started(UUID paymentId, String providerCode, String operation,
			String providerRequestNo, String providerOrderId, String method, String url, String requestHeaders,
			String requestBody) {
		var e = new ProviderApiExchangeEntity();
		e.exchangeId = UUID.randomUUID();
		e.paymentId = paymentId;
		e.providerCode = providerCode;
		e.operation = operation;
		e.providerRequestNo = providerRequestNo;
		e.providerOrderId = providerOrderId;
		e.httpMethod = method;
		e.requestUrl = url;
		e.requestHeaders = requestHeaders;
		e.requestBody = requestBody;
		e.startedAt = Instant.now();
		e.outcome = "STARTED";
		return e;
	}

	/**
	 * Retrieves exchange identifier.
	 *
	 * @return the resulting {@code UUID} value
	 */
	public UUID getExchangeId() {
		return exchangeId;
	}

	/**
	 * Executes the completed operation.
	 *
	 * @param status                  the payment or delivery status
	 * @param responseHeaders         the response headers value
	 * @param responseBody            the response body value
	 * @param providerResponseCode    the provider response code value
	 * @param providerResponseMessage the provider response message value
	 * @param outcome                 the outcome value
	 */
	public void completed(int status, String responseHeaders, String responseBody, String providerResponseCode,
			String providerResponseMessage, String outcome) {
		completed(status, responseHeaders, responseBody, providerResponseCode, providerResponseMessage, outcome, null);
	}

	/**
	 * Executes the completed operation.
	 *
	 * @param status                  the payment or delivery status
	 * @param responseHeaders         the response headers value
	 * @param responseBody            the response body value
	 * @param providerResponseCode    the provider response code value
	 * @param providerResponseMessage the provider response message value
	 * @param outcome                 the outcome value
	 * @param providerReferenceId     the provider reference identifier value
	 */
	public void completed(int status, String responseHeaders, String responseBody, String providerResponseCode,
			String providerResponseMessage, String outcome, String providerReferenceId) {
		this.responseHttpStatus = status;
		this.responseHeaders = responseHeaders;
		this.responseBody = responseBody;
		this.providerResponseCode = providerResponseCode;
		this.providerResponseMessage = providerResponseMessage;
		if (providerReferenceId != null && !providerReferenceId.isBlank())
			this.providerReferenceId = providerReferenceId;
		this.outcome = outcome;
		this.completedAt = Instant.now();
		this.durationMs = Math.max(0, completedAt.toEpochMilli() - startedAt.toEpochMilli());
	}

	/**
	 * Executes the failed operation.
	 *
	 * @param status          the payment or delivery status
	 * @param responseHeaders the response headers value
	 * @param responseBody    the response body value
	 * @param error           the failure that triggered error handling
	 * @param outcome         the outcome value
	 */
	public void failed(Integer status, String responseHeaders, String responseBody, Throwable error, String outcome) {
		this.responseHttpStatus = status;
		this.responseHeaders = responseHeaders;
		this.responseBody = responseBody;
		this.outcome = outcome;
		this.errorClass = error == null ? null : error.getClass().getName();
		this.errorMessage = error == null ? null : error.getMessage();
		this.completedAt = Instant.now();
		this.durationMs = Math.max(0, completedAt.toEpochMilli() - startedAt.toEpochMilli());
	}
}
