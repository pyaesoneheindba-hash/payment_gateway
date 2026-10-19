package com.paymentgateway.gateway.security.publictoken;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a public token security audit event exchanged by the platform.
 */
public record PublicTokenSecurityAuditEvent(
        UUID securityEventId,
        String correlationId,
        UUID merchantId,
        String subject,
        String jtiHash,
        String scopes,
        String httpMethod,
        String requestPath,
        Instant tokenExpiresAt,
        Boolean oneTime,
        Boolean replayDetected,
        String decision,
        String failureReason
) {}
