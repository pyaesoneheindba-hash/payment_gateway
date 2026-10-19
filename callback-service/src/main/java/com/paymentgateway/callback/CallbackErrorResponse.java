package com.paymentgateway.callback;

import java.time.Instant;

/**
 * Carries response data for callback error operations.
 */
public record CallbackErrorResponse(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        String correlationId) {
}
