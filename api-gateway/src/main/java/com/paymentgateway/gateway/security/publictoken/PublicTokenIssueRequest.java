package com.paymentgateway.gateway.security.publictoken;

import java.util.Set;
import java.util.UUID;

/**
 * Carries input data for public token issue requests.
 */
public record PublicTokenIssueRequest(
        String subject,
        Set<String> scopes,
        Long ttlSeconds,
        UUID paymentId,
        String merchantOrderId,
        Long amountMinor,
        String currency,
        String providerCode,
        String customerPhone,
        Boolean oneTime
) {}
