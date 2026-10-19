package com.paymentgateway.gateway.security;

import java.util.UUID;

/**
 * Represents a client security audit event exchanged by the platform.
 */
public record ClientSecurityAuditEvent(
        UUID securityEventId,
        String correlationId,
        UUID merchantId,
        String keyId,
        String httpMethod,
        String requestPath,
        Long requestTimestampEpoch,
        String nonceHash,
        String bodyHash,
        String idempotencyKeyHash,
        String signatureAlgorithm,
        Boolean signatureVerified,
        Boolean replayDetected,
        String decision,
        String failureReason
) {}
