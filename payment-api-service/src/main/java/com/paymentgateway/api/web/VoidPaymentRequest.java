package com.paymentgateway.api.web;

import java.util.Map;
/**
 * Carries input data for void payment requests.
 */
public record VoidPaymentRequest(Map<String,String> providerOptions) {}
