package com.paymentgateway.contract.provider;

import java.io.Serializable;
import java.util.Map;
import java.util.UUID;

/**
 * Carries command data for initiate payment processing.
 */
public record InitiatePaymentCommand(
        UUID paymentId,
        String merchantOrderId,
        String providerOrderId,
        long amountMinor,
        String currency,
        String description,
        Map<String, String> providerOptions
) implements Serializable {}
