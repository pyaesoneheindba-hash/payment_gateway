package com.paymentgateway.provider.ayapay.api;
/**
 * Carries response data for aya payment operations.
 */
public record AyaPaymentResponse(Integer err,String message,Data data) {
    /**
     * Immutable data carrier for data.
     */
    public record Data(String qrdata,String mmqrdata,String merchantId,String externalTransactionId,String referenceNumber,Long amount,String currency,String expiredAt) {}
}
