package com.paymentgateway.provider.ayapay.api;
/**
 * Carries input data for aya login requests.
 */
public record AyaLoginRequest(String phone,String password) {}
