package com.paymentgateway.contract.payment;

import java.io.Serializable;
import java.util.Map;
import java.util.UUID;

/**
 * Carries command data for create payment processing.
 */
public record CreatePaymentCommand(
        UUID merchantId,
        String merchantOrderId,
        long amountMinor,
        String currency,
        String description,
        String providerCode,
        Map<String, String> providerOptions,
        String idempotencyKey,
        String requestHash
) implements Serializable {}
