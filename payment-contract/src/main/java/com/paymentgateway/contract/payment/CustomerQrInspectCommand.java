package com.paymentgateway.contract.payment;

import java.io.Serializable;
import java.util.Map;
import java.util.UUID;

/**
 * Carries command data for customer QR inspect processing.
 */
public record CustomerQrInspectCommand(
        UUID merchantId,
        String providerCode,
        String qrData,
        String deviceId,
        String shopId,
        Map<String,String> providerOptions
) implements Serializable {}
