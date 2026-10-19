package com.paymentgateway.audit.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.paymentgateway.audit.entity.ApiCallLogEntity;
import com.paymentgateway.audit.repo.ApiCallLogRepository;

/**
 * Provides API call audit application services.
 */
@Service
public class ApiCallAuditService {
	private final ApiCallLogRepository repository;

	/**
	 * Creates a new {@code ApiCallAuditService} instance.
	 *
	 * @param repository the repository value
	 */
	public ApiCallAuditService(ApiCallLogRepository repository) {
		this.repository = repository;
	}

	/**
	 * Executes the start operation.
	 *
	 * @param serviceName     the service name value
	 * @param direction       the direction value
	 * @param sourceSystem    the source system value
	 * @param targetSystem    the target system value
	 * @param operation       the operation value
	 * @param method          the method value
	 * @param path            the path value
	 * @param query           the query value
	 * @param requestHeaders  the request headers value
	 * @param requestBody     the request body value
	 * @param correlationId   the correlation identifier used for request tracing
	 * @param merchantId      the merchant identifier
	 * @param paymentId       the payment identifier
	 * @param providerOrderId the provider order identifier value
	 * @return the resulting {@code UUID} value
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public UUID start(String serviceName, String direction, String sourceSystem, String targetSystem, String operation,
			String method, String path, String query, String requestHeaders, String requestBody, String correlationId,
			UUID merchantId, UUID paymentId, String providerOrderId) {
		return repository.saveAndFlush(
				ApiCallLogEntity.started(serviceName, direction, sourceSystem, targetSystem, operation, method, path,
						query, requestHeaders, requestBody, correlationId, merchantId, paymentId, providerOrderId))
				.getCallId();
	}

	/**
	 * Executes the complete operation.
	 *
	 * @param callId           the call identifier identifier
	 * @param finalRequestBody the final request body value
	 * @param status           the payment or delivery status
	 * @param responseHeaders  the response headers value
	 * @param responseBody     the response body value
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void complete(UUID callId, String finalRequestBody, int status, String responseHeaders,
			String responseBody) {
		repository.findById(callId)
				.ifPresent(e -> e.completed(finalRequestBody, status, responseHeaders, responseBody));
	}

	/**
	 * Executes the fail operation.
	 *
	 * @param callId           the call identifier identifier
	 * @param finalRequestBody the final request body value
	 * @param status           the payment or delivery status
	 * @param responseBody     the response body value
	 * @param error            the failure that triggered error handling
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void fail(UUID callId, String finalRequestBody, Integer status, String responseBody, Throwable error) {
		repository.findById(callId).ifPresent(e -> e.failed(finalRequestBody, status, responseBody, error));
	}
}
