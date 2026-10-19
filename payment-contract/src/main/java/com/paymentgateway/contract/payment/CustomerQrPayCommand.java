package com.paymentgateway.contract.payment;

import java.io.Serializable;
import java.util.Map;
import java.util.UUID;

/**
 * Carries command data for customer QR pay processing.
 */
public record CustomerQrPayCommand(
        UUID merchantId,
        String merchantOrderId,
        long amountMinor,
        String currency,
        String description,
        String providerCode,
        String customerPhone,
        Map<String,String> providerOptions,
        String idempotencyKey,
        String requestHash
) implements Serializable {}
