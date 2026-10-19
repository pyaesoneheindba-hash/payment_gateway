package com.paymentgateway.contract.provider;

/**
 * Defines operations provided by the customer present QR provider RPC service.
 */
public interface CustomerPresentQrProviderRpcService {
    /**
     * Executes the inspect customer QR operation.
     *
     * @param command the operation command
     * @return the resulting {@code ProviderCustomerQrInspectResult} value
     */
    ProviderCustomerQrInspectResult inspectCustomerQr(ProviderCustomerQrInspectCommand command);
    /**
     * Executes the pay customer QR operation.
     *
     * @param command the operation command
     * @return the resulting {@code InitiatePaymentResult} value
     */
    InitiatePaymentResult payCustomerQr(ProviderCustomerQrPaymentCommand command);
}
