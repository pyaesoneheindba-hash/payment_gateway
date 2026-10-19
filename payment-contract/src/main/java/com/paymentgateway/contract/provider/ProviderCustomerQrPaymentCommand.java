package com.paymentgateway.contract.provider;

import java.io.Serializable;
import java.util.Map;
import java.util.UUID;

/**
 * Carries command data for provider customer QR payment processing.
 */
public record ProviderCustomerQrPaymentCommand(
        UUID paymentId,
        String providerOrderId,
        long amountMinor,
        String currency,
        String customerPhone,
        String description,
        Map<String, String> providerOptions
) implements Serializable {}
