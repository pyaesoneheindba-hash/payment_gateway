package com.paymentgateway.contract.payment;

import com.paymentgateway.common.PaymentStatus;
import java.io.Serializable;
import java.util.Map;
import java.util.UUID;

/**
 * Provides a read-only view of payment data.
 */
public record PaymentView(
        UUID paymentId,
        UUID merchantId,
        String merchantOrderId,
        long amountMinor,
        String currency,
        PaymentStatus status,
        String qr,
        String providerCode,
        String providerOrderId,
        String providerReferenceId,
        String nextAction,
        String redirectUrl,
        Map<String, String> formFields
) implements Serializable {}
