package com.paymentgateway.api.webhook;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Carries input data for webhook endpoint requests.
 */
public record WebhookEndpointRequest(
        @NotBlank @Size(max = 2000) String url,
        @Size(max = 255) String description
) {}
