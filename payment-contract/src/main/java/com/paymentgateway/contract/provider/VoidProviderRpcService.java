package com.paymentgateway.contract.provider;

/**
 * Defines operations provided by the void provider RPC service.
 */
public interface VoidProviderRpcService {
	/**
	 * Voids payment.
	 *
	 * @param command the operation command
	 * @return the resulting {@code ProviderVoidResult} value
	 */
	ProviderVoidResult voidPayment(ProviderVoidCommand command);
}
