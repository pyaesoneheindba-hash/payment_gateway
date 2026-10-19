package com.paymentgateway.callback;

import com.paymentgateway.contract.error.PaymentGatewayException;
import com.paymentgateway.contract.error.PaymentNotFoundException;
import com.paymentgateway.contract.error.ProviderUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Provider callback exception boundary. Internal exception details are logged, not returned to providers. */
@RestControllerAdvice
public class CallbackExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(CallbackExceptionHandler.class);

    /**
     * Maps shared payment-domain failures raised while processing provider callbacks.
     *
     * @param e payment-domain exception
     * @param request current callback HTTP request
     * @return sanitized callback error response
     */
    @ExceptionHandler(PaymentGatewayException.class)
    public ResponseEntity<CallbackErrorResponse> domain(PaymentGatewayException e, HttpServletRequest request) {
        HttpStatus status = e instanceof PaymentNotFoundException ? HttpStatus.NOT_FOUND
                : e instanceof ProviderUnavailableException ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.CONFLICT;
        return response(status, e.getErrorCode(), e.getMessage(), request, e, status.is5xxServerError());
    }

    /**
     * Handles invalid provider callback input that can safely be reported as a bad request.
     *
     * @param e invalid-argument exception
     * @param request current callback HTTP request
     * @return HTTP 400 callback error response
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<CallbackErrorResponse> invalid(IllegalArgumentException e, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_CALLBACK", safe(e.getMessage(), "Invalid callback"),
                request, e, false);
    }

    /**
     * Final callback exception boundary that translates known upstream failures and hides internals.
     *
     * @param e unhandled callback-processing exception
     * @param request current callback HTTP request
     * @return normalized and sanitized callback error response
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<CallbackErrorResponse> unexpected(Exception e, HttpServletRequest request) {
        for (Throwable current = e; current != null; current = current.getCause()) {
            if (current instanceof PaymentGatewayException domain) return domain(domain, request);
            String haystack = (current.getClass().getName() + " " + safe(current.getMessage(), "")).toLowerCase(Locale.ROOT);
            if (haystack.contains("timeout") || haystack.contains("no provider available")
                    || haystack.contains("no servers available") || haystack.contains("rpcexception")) {
                return response(HttpStatus.SERVICE_UNAVAILABLE, "UPSTREAM_UNAVAILABLE",
                        "Payment processing service is temporarily unavailable", request, e, true);
            }
        }
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected internal error occurred", request, e, true);
    }

    /**
     * Builds the callback error envelope and logs the original exception server-side.
     *
     * @param status HTTP status to return
     * @param code stable machine-readable error code
     * @param message safe provider-facing message
     * @param request current callback HTTP request
     * @param error original exception for server-side logging
     * @param internal whether the failure is internal or infrastructure-related
     * @return response entity containing the callback error envelope
     */
    private ResponseEntity<CallbackErrorResponse> response(HttpStatus status, String code, String message,
            HttpServletRequest request, Throwable error, boolean internal) {
        String correlationId = trimToNull(request.getHeader("X-Correlation-Id"));
        if (internal) {
            log.error("Callback failure correlationId={} code={} path={}", correlationId, code, request.getRequestURI(), error);
        }
        else {
            log.warn("Callback rejected correlationId={} code={} path={} message={}",
                    correlationId, code, request.getRequestURI(), message);
        }
        return ResponseEntity.status(status).body(new CallbackErrorResponse(
                Instant.now(), status.value(), code, message, request.getRequestURI(), correlationId));
    }

    /**
     * Sanitizes callback error text by removing line breaks and limiting its length.
     *
     * @param value candidate message
     * @param fallback fallback text when the candidate is blank
     * @return sanitized message
     */
    private static String safe(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String cleaned = value.replace('\n', ' ').replace('\r', ' ').trim();
        return cleaned.length() <= 500 ? cleaned : cleaned.substring(0, 500);
    }

    /**
     * Trims a value and converts blank text to {@code null}.
     *
     * @param value input value
     * @return trimmed value, or {@code null} when empty
     */
    private static String trimToNull(String value) {
        if (value == null) return null;
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }
}
