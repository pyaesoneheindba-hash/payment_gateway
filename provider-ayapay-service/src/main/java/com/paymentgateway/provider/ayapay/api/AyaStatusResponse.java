package com.paymentgateway.provider.ayapay.api;
/**
 * Carries response data for aya status operations.
 */
public record AyaStatusResponse(Integer err,String message,String status,String transRefId,CallBackInfo callBackInfo) {
    /**
     * Immutable data carrier for call back info.
     */
    public record CallBackInfo(String paymentResult,String checksum,String externalTransactionId,Object refundResult,Boolean payByOtherPay,String debitorName,String mmqrRefId,String walletName) {}
}
