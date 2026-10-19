package com.paymentgateway.contract.provider;

import com.paymentgateway.common.ProviderOutcome;
import java.io.Serializable;
import java.util.Map;

/**
 * Represents the result of initiate payment processing.
 */
public record InitiatePaymentResult(
        ProviderOutcome outcome,
        String providerCode,
        String providerOrderId,
        String providerReferenceId,
        String providerRequestNo,
        String nextAction,
        String qr,
        String redirectUrl,
        Map<String, String> formFields,
        String responseCode,
        String responseMessage
) implements Serializable {}
