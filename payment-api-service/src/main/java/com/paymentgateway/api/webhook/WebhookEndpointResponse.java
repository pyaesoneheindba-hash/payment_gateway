package com.paymentgateway.api.webhook;

import java.time.Instant;
import java.util.UUID;

/**
 * Carries response data for webhook endpoint operations.
 */
public record WebhookEndpointResponse(
        UUID endpointId,
        String eventType,
        String url,
        String description,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
    /**
     * Executes the from operation.
     *
     * @param e the exception that caused the operation to fail
     * @return the resulting {@code WebhookEndpointResponse} value
     */
    static WebhookEndpointResponse from(MerchantWebhookEndpointRecord e) {
        return new WebhookEndpointResponse(e.endpointId(), e.eventType(), e.url(), e.description(),
                e.status(), e.createdAt(), e.updatedAt());
    }
}
