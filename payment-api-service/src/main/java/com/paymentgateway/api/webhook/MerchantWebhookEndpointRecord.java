package com.paymentgateway.api.webhook;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable data carrier for merchant webhook endpoint record.
 */
public record MerchantWebhookEndpointRecord(
        UUID endpointId,
        UUID merchantId,
        String eventType,
        String url,
        String description,
        String status,
        Instant createdAt,
        Instant updatedAt
) {}
