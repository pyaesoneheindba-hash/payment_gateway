package com.paymentgateway.api.web;

import java.time.Instant;
import java.util.Map;

/** Stable external API error envelope. Never include stack traces or internal exception class names. */
public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        String correlationId,
        Map<String, Object> details) {
}
