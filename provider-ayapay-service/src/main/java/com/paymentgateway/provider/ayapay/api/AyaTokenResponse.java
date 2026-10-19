package com.paymentgateway.provider.ayapay.api;
/**
 * Carries response data for aya token operations.
 */
public record AyaTokenResponse(String access_token,String scope,String token_type,long expires_in) {}
