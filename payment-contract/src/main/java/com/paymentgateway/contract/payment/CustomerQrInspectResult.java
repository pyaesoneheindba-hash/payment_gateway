package com.paymentgateway.contract.payment;

import java.io.Serializable;

/**
 * Represents the result of customer QR inspect processing.
 */
public record CustomerQrInspectResult(
        String providerCode,
        String customerPhone,
        String customerReference,
        String rawProviderDataJson,
        String responseCode,
        String message
) implements Serializable {}
