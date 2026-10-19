package com.paymentgateway.contract.provider;

import java.io.Serializable;
import java.util.Map;
import java.util.UUID;

/**
 * Carries command data for provider void processing.
 */
public record ProviderVoidCommand(
        UUID paymentId,
        String providerOrderId,
        String providerReferenceId,
        long amountMinor,
        String currency,
        Map<String, String> providerOptions
) implements Serializable {}
