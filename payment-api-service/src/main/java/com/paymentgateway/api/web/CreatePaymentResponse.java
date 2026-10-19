package com.paymentgateway.api.web;

import com.paymentgateway.common.PaymentStatus;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;

/**
 * Carries response data for create payment operations.
 */
public record CreatePaymentResponse(
        UUID paymentId,
        PaymentStatus status,
        String qr,
        String providerCode,
        String nextAction,
        String redirectUrl,
        Map<String,String> formFields,
        String statusUrl,
        String eventsUrl,
        String checkoutUrl,
        Instant expiresAt,
        String message
) {}
