package com.paymentgateway.audit.entity;

import java.time.Instant;
import java.util.UUID;

import com.paymentgateway.persistence.BaseAuditableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Represents the persisted API call log state.
 */
@Entity
@Table(name = "api_call_log")
public class ApiCallLogEntity extends BaseAuditableEntity {
	@Id
	@Column(name = "call_id", nullable = false)
	private UUID callId;
	@Column(name = "correlation_id", length = 128)
	private String correlationId;
	@Column(name = "service_name", nullable = false, length = 100)
	private String serviceName;
	@Column(name = "direction", nullable = false, length = 16)
	private String direction;
	@Column(name = "source_system", length = 100)
	private String sourceSystem;
	@Column(name = "target_system", length = 100)
	private String targetSystem;
	@Column(name = "operation", nullable = false, length = 100)
	private String operation;
	@Column(name = "http_method", nullable = false, length = 16)
	private String httpMethod;
	@Column(name = "request_path", nullable = false, length = 1000)
	private String requestPath;
	@Column(name = "query_string", columnDefinition = "TEXT")
	private String queryString;
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
	@Column(name = "result_status", length = 32)
	private String resultStatus;
	@Column(name = "error_class", length = 255)
	private String errorClass;
	@Column(name = "error_message", columnDefinition = "TEXT")
	private String errorMessage;
	@Column(name = "merchant_id")
	private UUID merchantId;
	@Column(name = "payment_id")
	private UUID paymentId;
	@Column(name = "provider_order_id", length = 64)
	private String providerOrderId;
	@Column(name = "started_at", nullable = false)
	private Instant startedAt;
	@Column(name = "completed_at")
	private Instant completedAt;
	@Column(name = "duration_ms")
	private Long durationMs;

	/**
	 * Creates a new {@code ApiCallLogEntity} instance.
	 */
	protected ApiCallLogEntity() {
	}

	/**
	 * Executes the started operation.
	 *
	 * @param serviceName     the service name value
	 * @param direction       the direction value
	 * @param sourceSystem    the source system value
	 * @param targetSystem    the target system value
	 * @param operation       the operation value
	 * @param method          the method value
	 * @param path            the path value
	 * @param queryString     the query string value
	 * @param requestHeaders  the request headers value
	 * @param requestBody     the request body value
	 * @param correlationId   the correlation identifier used for request tracing
	 * @param merchantId      the merchant identifier
	 * @param paymentId       the payment identifier
	 * @param providerOrderId the provider order identifier value
	 * @return the resulting {@code ApiCallLogEntity} value
	 */
	public static ApiCallLogEntity started(String serviceName, String direction, String sourceSystem,
			String targetSystem, String operation, String method, String path, String queryString,
			String requestHeaders, String requestBody, String correlationId, UUID merchantId, UUID paymentId,
			String providerOrderId) {
		var e = new ApiCallLogEntity();
		e.callId = UUID.randomUUID();
		e.serviceName = serviceName;
		e.direction = direction;
		e.sourceSystem = sourceSystem;
		e.targetSystem = targetSystem;
		e.operation = operation;
		e.httpMethod = method;
		e.requestPath = path;
		e.queryString = queryString;
		e.requestHeaders = requestHeaders;
		e.requestBody = requestBody;
		e.correlationId = correlationId;
		e.merchantId = merchantId;
		e.paymentId = paymentId;
		e.providerOrderId = providerOrderId;
		e.startedAt = Instant.now();
		e.resultStatus = "STARTED";
		return e;
	}

	/**
	 * Retrieves call identifier.
	 *
	 * @return the resulting {@code UUID} value
	 */
	public UUID getCallId() {
		return callId;
	}

	/**
	 * Executes the completed operation.
	 *
	 * @param finalRequestBody the final request body value
	 * @param httpStatus       the HTTP status value
	 * @param responseHeaders  the response headers value
	 * @param responseBody     the response body value
	 */
	public void completed(String finalRequestBody, int httpStatus, String responseHeaders, String responseBody) {
		if (finalRequestBody != null)
			this.requestBody = finalRequestBody;
		this.responseHttpStatus = httpStatus;
		this.responseHeaders = responseHeaders;
		this.responseBody = responseBody;
		this.resultStatus = "COMPLETED";
		this.completedAt = Instant.now();
		this.durationMs = Math.max(0, completedAt.toEpochMilli() - startedAt.toEpochMilli());
	}

	/**
	 * Executes the failed operation.
	 *
	 * @param finalRequestBody the final request body value
	 * @param httpStatus       the HTTP status value
	 * @param responseBody     the response body value
	 * @param error            the failure that triggered error handling
	 */
	public void failed(String finalRequestBody, Integer httpStatus, String responseBody, Throwable error) {
		if (finalRequestBody != null)
			this.requestBody = finalRequestBody;
		this.responseHttpStatus = httpStatus;
		this.responseBody = responseBody;
		this.resultStatus = "FAILED";
		this.errorClass = error == null ? null : error.getClass().getName();
		this.errorMessage = error == null ? null : error.getMessage();
		this.completedAt = Instant.now();
		this.durationMs = Math.max(0, completedAt.toEpochMilli() - startedAt.toEpochMilli());
	}
}
