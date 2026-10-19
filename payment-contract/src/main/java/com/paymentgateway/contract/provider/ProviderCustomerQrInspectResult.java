package com.paymentgateway.contract.provider;

import com.paymentgateway.common.ProviderOutcome;
import java.io.Serializable;

/**
 * Represents the result of provider customer QR inspect processing.
 */
public record ProviderCustomerQrInspectResult(
        ProviderOutcome outcome,
        String providerCode,
        String customerPhone,
        String customerReference,
        String rawProviderDataJson,
        String responseCode,
        String responseMessage
) implements Serializable {}
