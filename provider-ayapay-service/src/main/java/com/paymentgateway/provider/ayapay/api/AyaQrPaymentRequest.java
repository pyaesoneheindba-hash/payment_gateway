package com.paymentgateway.provider.ayapay.api;
/**
 * Carries input data for aya QR payment requests.
 */
public record AyaQrPaymentRequest(long amount,String currency,String externalTransactionId,Object externalAdditionalData,String serviceCode,Integer timelimit,Boolean MMQR) {}
