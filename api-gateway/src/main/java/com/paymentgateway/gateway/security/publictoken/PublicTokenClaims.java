package com.paymentgateway.gateway.security.publictoken;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable data carrier for public token claims.
 */
public record PublicTokenClaims(
        UUID merchantId,
        String subject,
        String jti,
        Set<String> scopes,
        Instant issuedAt,
        Instant notBefore,
        Instant expiresAt,
        UUID paymentId,
        String merchantOrderId,
        Long amountMinor,
        String currency,
        String providerCode,
        String customerPhone,
        boolean oneTime
) {}
