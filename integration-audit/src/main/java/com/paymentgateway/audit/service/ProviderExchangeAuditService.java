package com.paymentgateway.audit.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.paymentgateway.audit.entity.ProviderApiExchangeEntity;
import com.paymentgateway.audit.repo.ProviderApiExchangeRepository;

/**
 * Provides provider exchange audit application services.
 */
@Service
public class ProviderExchangeAuditService {
	private final ProviderApiExchangeRepository repository;

	/**
	 * Creates a new {@code ProviderExchangeAuditService} instance.
	 *
	 * @param repository the repository value
	 */
	public ProviderExchangeAuditService(ProviderApiExchangeRepository repository) {
		this.repository = repository;
	}

	/**
	 * Executes the start operation.
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
	 * @return the resulting {@code UUID} value
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public UUID start(UUID paymentId, String providerCode, String operation, String providerRequestNo,
			String providerOrderId, String method, String url, String requestHeaders, String requestBody) {
		return repository.saveAndFlush(ProviderApiExchangeEntity.started(paymentId, providerCode, operation,
				providerRequestNo, providerOrderId, method, url, requestHeaders, requestBody)).getExchangeId();
	}

	/**
	 * Executes the complete operation.
	 *
	 * @param exchangeId              the exchange identifier identifier
	 * @param status                  the payment or delivery status
	 * @param responseHeaders         the response headers value
	 * @param responseBody            the response body value
	 * @param providerResponseCode    the provider response code value
	 * @param providerResponseMessage the provider response message value
	 * @param outcome                 the outcome value
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void complete(UUID exchangeId, int status, String responseHeaders, String responseBody,
			String providerResponseCode, String providerResponseMessage, String outcome) {
		repository.findById(exchangeId).ifPresent(e -> e.completed(status, responseHeaders, responseBody,
				providerResponseCode, providerResponseMessage, outcome));
	}

	/**
	 * Executes the complete operation.
	 *
	 * @param exchangeId              the exchange identifier identifier
	 * @param status                  the payment or delivery status
	 * @param responseHeaders         the response headers value
	 * @param responseBody            the response body value
	 * @param providerResponseCode    the provider response code value
	 * @param providerResponseMessage the provider response message value
	 * @param outcome                 the outcome value
	 * @param providerReferenceId     the provider reference identifier value
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void complete(UUID exchangeId, int status, String responseHeaders, String responseBody,
			String providerResponseCode, String providerResponseMessage, String outcome, String providerReferenceId) {
		repository.findById(exchangeId).ifPresent(e -> e.completed(status, responseHeaders, responseBody,
				providerResponseCode, providerResponseMessage, outcome, providerReferenceId));
	}

	/**
	 * Executes the fail operation.
	 *
	 * @param exchangeId      the exchange identifier identifier
	 * @param status          the payment or delivery status
	 * @param responseHeaders the response headers value
	 * @param responseBody    the response body value
	 * @param error           the failure that triggered error handling
	 * @param outcome         the outcome value
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void fail(UUID exchangeId, Integer status, String responseHeaders, String responseBody, Throwable error,
			String outcome) {
		repository.findById(exchangeId).ifPresent(e -> e.failed(status, responseHeaders, responseBody, error, outcome));
	}
}
