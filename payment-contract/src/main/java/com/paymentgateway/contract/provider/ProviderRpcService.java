package com.paymentgateway.contract.provider;

/**
 * Defines operations provided by the provider RPC service.
 */
public interface ProviderRpcService {
	/**
	 * Executes the descriptor operation.
	 *
	 * @return the resulting {@code ProviderDescriptor} value
	 */
	ProviderDescriptor descriptor();

	/**
	 * Executes the initiate operation.
	 *
	 * @param command the operation command
	 * @return the resulting {@code InitiatePaymentResult} value
	 */
	InitiatePaymentResult initiate(InitiatePaymentCommand command);

	/**
	 * Executes the enquire operation.
	 *
	 * @param command the operation command
	 * @return the resulting {@code EnquirePaymentResult} value
	 */
	EnquirePaymentResult enquire(EnquirePaymentCommand command);
}
