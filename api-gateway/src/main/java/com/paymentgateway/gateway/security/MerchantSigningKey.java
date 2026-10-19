package com.paymentgateway.gateway.security;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable data carrier for merchant signing key.
 */
public record MerchantSigningKey(
        String keyId,
        UUID merchantId,
        String algorithm,
        String publicKey,
        String status,
        String merchantStatus,
        Instant validFrom,
        Instant validUntil
) {}
