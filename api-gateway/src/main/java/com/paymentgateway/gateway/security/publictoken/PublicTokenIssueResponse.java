package com.paymentgateway.gateway.security.publictoken;

import java.time.Instant;
import java.util.Set;

/**
 * Carries response data for public token issue operations.
 */
public record PublicTokenIssueResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        Instant expiresAt,
        String jti,
        Set<String> scopes
) {}
