package com.paymentgateway.provider.ayapay.api;
/**
 * Carries response data for aya push operations.
 */
public record AyaPushResponse(Integer err,String message,Data data) {
    /**
     * Immutable data carrier for data.
     */
    public record Data(String externalTransactionId,String referenceNumber) {}
}
