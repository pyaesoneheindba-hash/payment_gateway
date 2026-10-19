package com.paymentgateway.contract.payment;

import com.paymentgateway.common.ProviderCapability;
import java.io.Serializable;
import java.util.Set;

/**
 * Summarizes provider information.
 */
public record ProviderSummary(
        String providerCode,
        String displayName,
        String dubboGroup,
        boolean enabled,
        Set<ProviderCapability> capabilities
) implements Serializable {}
