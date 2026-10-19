package com.paymentgateway.contract.provider;

/**
 * Defines operations provided by the refund provider RPC service.
 */
public interface RefundProviderRpcService {
    /**
     * Executes the refund operation.
     *
     * @param command the operation command
     * @return the resulting {@code ProviderRefundResult} value
     */
    ProviderRefundResult refund(ProviderRefundCommand command);
}
