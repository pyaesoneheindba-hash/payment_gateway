package com.paymentgateway.contract.provider;

import java.io.Serializable;
import java.util.Map;
import java.util.UUID;

/**
 * Carries command data for provider customer QR inspect processing.
 */
public record ProviderCustomerQrInspectCommand(
        UUID requestId,
        String qrData,
        String deviceId,
        String shopId,
        Map<String, String> providerOptions
) implements Serializable {}
