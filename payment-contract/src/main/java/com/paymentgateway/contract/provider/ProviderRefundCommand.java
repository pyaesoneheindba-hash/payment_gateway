package com.paymentgateway.contract.provider;

import java.io.Serializable;
import java.util.Map;
import java.util.UUID;

/**
 * Carries command data for provider refund processing.
 */
public record ProviderRefundCommand(
        UUID paymentId,
        UUID refundId,
        String providerOrderId,
        String providerReferenceId,
        String merchantRefundId,
        long amountMinor,
        long originalAmountMinor,
        String currency,
        Map<String, String> providerOptions
) implements Serializable {}
