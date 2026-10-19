package com.paymentgateway.provider.ayapay.api;
/**
 * Carries input data for aya refund requests.
 */
public record AyaRefundRequest(String referenceNumber,String externalTransactionId) {}
