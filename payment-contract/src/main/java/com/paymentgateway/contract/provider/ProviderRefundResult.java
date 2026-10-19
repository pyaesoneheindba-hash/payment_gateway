package com.paymentgateway.contract.provider;

import com.paymentgateway.common.ProviderOutcome;
import java.io.Serializable;

/**
 * Represents the result of provider refund processing.
 */
public record ProviderRefundResult(
        ProviderOutcome outcome,
        String providerCode,
        String providerRefundReferenceId,
        String providerStatus,
        String responseCode,
        String responseMessage
) implements Serializable {}
