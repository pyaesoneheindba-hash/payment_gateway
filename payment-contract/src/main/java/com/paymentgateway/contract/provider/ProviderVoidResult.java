package com.paymentgateway.contract.provider;

import com.paymentgateway.common.ProviderOutcome;
import java.io.Serializable;

/**
 * Represents the result of provider void processing.
 */
public record ProviderVoidResult(
        ProviderOutcome outcome,
        String providerCode,
        String providerReferenceId,
        String providerStatus,
        String responseCode,
        String responseMessage
) implements Serializable {}
