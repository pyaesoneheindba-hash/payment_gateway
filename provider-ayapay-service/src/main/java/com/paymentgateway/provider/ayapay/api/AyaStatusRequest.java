package com.paymentgateway.provider.ayapay.api;
/**
 * Carries input data for aya status requests.
 */
public record AyaStatusRequest(String referenceNumber,String externalTransactionId) {}
