package com.paymentgateway.contract.payment;

import com.paymentgateway.common.PaymentStatus;
import java.io.Serializable;
import java.util.Map;
import java.util.UUID;

/**
 * Represents the result of create payment processing.
 */
public record CreatePaymentResult(
        UUID paymentId,
        PaymentStatus status,
        String qr,
        String providerCode,
        String nextAction,
        String redirectUrl,
        Map<String, String> formFields,
        String message
) implements Serializable {}
