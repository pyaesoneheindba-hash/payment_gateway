package com.paymentgateway.api.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * Carries input data for refund payment requests.
 */
public record RefundPaymentRequest(@NotBlank @Size(max=128) String merchantRefundId,@Min(1) long amountMinor,Map<String,String> providerOptions) {}
