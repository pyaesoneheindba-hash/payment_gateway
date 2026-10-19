package com.paymentgateway.provider.ayapay.api;
/**
 * Carries response data for aya login operations.
 */
public record AyaLoginResponse(Integer err,String message,TokenValue token,TokenValue refreshToken) {
    /**
     * Immutable data carrier for token value.
     */
    public record TokenValue(String token,String expiredAt) {}
}
