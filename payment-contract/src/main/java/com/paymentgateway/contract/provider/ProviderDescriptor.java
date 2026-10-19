package com.paymentgateway.contract.provider;

import com.paymentgateway.common.ProviderCapability;
import java.io.Serializable;
import java.util.Set;

/**
 * Immutable data carrier for provider descriptor.
 */
public record ProviderDescriptor(
        String providerCode,
        String displayName,
        Set<ProviderCapability> capabilities
) implements Serializable {}
