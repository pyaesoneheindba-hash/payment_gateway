package com.paymentgateway.api.web;

import java.nio.file.AccessDeniedException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import com.paymentgateway.api.portal.KeycloakPortalIdentityProvisioner.IdentityConflictException;
import com.paymentgateway.api.portal.KeycloakPortalIdentityProvisioner.IdentityMissingException;
import com.paymentgateway.api.portal.KeycloakPortalIdentityProvisioner.IdentityProvisioningException;
import com.paymentgateway.api.portal.MerchantOnboardingService.MerchantOnboardingConflictException;
import com.paymentgateway.contract.error.IdempotencyConflictException;
import com.paymentgateway.contract.error.InvalidPaymentStateException;
import com.paymentgateway.contract.error.PaymentGatewayException;
import com.paymentgateway.contract.error.PaymentNotFoundException;
import com.paymentgateway.contract.error.ProviderCapabilityException;
import com.paymentgateway.contract.error.ProviderNotAvailableException;
import com.paymentgateway.contract.error.ProviderOperationRejectedException;
import com.paymentgateway.contract.error.ProviderUnavailableException;
import com.paymentgateway.contract.payment.DuplicateMerchantOrderException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

/**
 * One external exception boundary for payment-api-service.
 *
 * Rules:
 * - stable machine-readable error code;
 * - X-Correlation-Id is echoed when supplied by api-gateway;
 * - validation/business failures keep useful safe messages;
 * - infrastructure/internal failures never leak stack traces, SQL, secrets or provider credentials.
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    /**
     * Maps a shared payment-domain exception to the stable external API error contract.
     *
     * @param e domain exception raised by the payment contract or core service
     * @param request current HTTP request used for path and correlation metadata
     * @return sanitized HTTP error response
     */
    @ExceptionHandler(PaymentGatewayException.class)
    public ResponseEntity<ApiErrorResponse> domain(PaymentGatewayException e, HttpServletRequest request) {
        return domainResponse(e, request);
    }

    /**
     * Converts bean-validation field errors into a deterministic {@code VALIDATION_ERROR} response.
     *
     * @param e validation exception raised for an invalid request body
     * @param request current HTTP request
     * @return HTTP 400 response containing field-level validation details
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> beanValidation(MethodArgumentNotValidException e, HttpServletRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(error -> fields.putIfAbsent(
                error.getField(), safe(error.getDefaultMessage(), "Invalid value")));
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", request,
                fields.isEmpty() ? Map.of() : Map.of("fields", fields), e, false);
    }

    /**
     * Converts method or parameter constraint violations into a deterministic validation response.
     *
     * @param e constraint violation exception
     * @param request current HTTP request
     * @return HTTP 400 response containing violated parameter paths
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> constraintValidation(ConstraintViolationException e, HttpServletRequest request) {
        Map<String, Object> violations = new LinkedHashMap<>();
        e.getConstraintViolations().forEach(v -> violations.put(
                String.valueOf(v.getPropertyPath()), v.getMessage()));
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", request,
                violations.isEmpty() ? Map.of() : Map.of("fields", violations), e, false);
    }

    /**
     * Handles a required request header that was not supplied by the caller.
     *
     * @param e missing-header exception
     * @param request current HTTP request
     * @return HTTP 400 response identifying the missing header
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiErrorResponse> missingHeader(MissingRequestHeaderException e, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "MISSING_REQUIRED_HEADER",
                "Required request header is missing: " + e.getHeaderName(), request,
                Map.of("header", e.getHeaderName()), e, false);
    }

    /**
     * Handles a required query or form parameter that was not supplied by the caller.
     *
     * @param e missing-parameter exception
     * @param request current HTTP request
     * @return HTTP 400 response identifying the missing parameter
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> missingParameter(MissingServletRequestParameterException e, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "MISSING_REQUIRED_PARAMETER",
                "Required request parameter is missing: " + e.getParameterName(), request,
                Map.of("parameter", e.getParameterName()), e, false);
    }

    /**
     * Handles request parameter values that cannot be converted to the required Java type.
     *
     * @param e type-mismatch exception
     * @param request current HTTP request
     * @return HTTP 400 response identifying the invalid parameter
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> typeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER",
                "Invalid value for parameter: " + e.getName(), request,
                Map.of("parameter", e.getName()), e, false);
    }

    /**
     * Handles missing or malformed JSON request bodies.
     *
     * @param e message-conversion exception
     * @param request current HTTP request
     * @return HTTP 400 malformed-JSON response
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> malformedJson(HttpMessageNotReadableException e, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "MALFORMED_JSON",
                "Request body is missing or contains malformed JSON", request, Map.of(), e, false);
    }

    /**
     * Handles unsupported request content types.
     *
     * @param e media-type exception
     * @param request current HTTP request
     * @return HTTP 415 response
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> unsupportedMediaType(HttpMediaTypeNotSupportedException e, HttpServletRequest request) {
        return response(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                "Request Content-Type is not supported", request, Map.of(), e, false);
    }

    /**
     * Handles use of an HTTP method that is not supported by the target resource.
     *
     * @param e method-not-supported exception
     * @param request current HTTP request
     * @return HTTP 405 response
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> methodNotAllowed(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        return response(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                "HTTP method is not supported for this resource", request, Map.of(), e, false);
    }

    /**
     * Preserves an explicit Spring response status while normalizing the response body.
     *
     * @param e response-status exception
     * @param request current HTTP request
     * @return normalized API error response using the requested HTTP status
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> responseStatus(ResponseStatusException e, HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        String code = status == HttpStatus.FORBIDDEN ? "FORBIDDEN"
                : status == HttpStatus.UNAUTHORIZED ? "UNAUTHORIZED"
                : "REQUEST_REJECTED";
        String message = safe(e.getReason(), status.getReasonPhrase());
        return response(status, code, message, request, Map.of(), e, status.is5xxServerError());
    }

    /**
     * Handles authorization failures raised after authentication has succeeded.
     *
     * @param e access-denied exception
     * @param request current HTTP request
     * @return HTTP 403 response
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> accessDenied(AccessDeniedException e, HttpServletRequest request) {
        return response(HttpStatus.FORBIDDEN, "FORBIDDEN", "You are not authorized for this operation",
                request, Map.of(), e, false);
    }

    /**
     * Handles identity conflicts reported while provisioning a portal user.
     *
     * @param e identity-conflict exception
     * @param request current HTTP request
     * @return HTTP 409 response
     */
    @ExceptionHandler(IdentityConflictException.class)
    public ResponseEntity<ApiErrorResponse> identityConflict(IdentityConflictException e, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "IDENTITY_CONFLICT", safe(e.getMessage(), "Identity already exists"),
                request, Map.of(), e, false);
    }

    /**
     * Handles attempts to operate on an identity that does not exist.
     *
     * @param e identity-missing exception
     * @param request current HTTP request
     * @return HTTP 404 response
     */
    @ExceptionHandler(IdentityMissingException.class)
    public ResponseEntity<ApiErrorResponse> identityMissing(IdentityMissingException e, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "IDENTITY_NOT_FOUND", safe(e.getMessage(), "Identity was not found"),
                request, Map.of(), e, false);
    }

    /**
     * Handles failures returned by the external identity provider.
     *
     * @param e identity provisioning exception
     * @param request current HTTP request
     * @return sanitized HTTP 502 response
     */
    @ExceptionHandler(IdentityProvisioningException.class)
    public ResponseEntity<ApiErrorResponse> identityProviderFailure(IdentityProvisioningException e, HttpServletRequest request) {
        return response(HttpStatus.BAD_GATEWAY, "IDENTITY_PROVIDER_ERROR",
                "Identity provider operation failed", request, Map.of(), e, true);
    }

    /**
     * Handles merchant-onboarding conflicts such as duplicate or incompatible records.
     *
     * @param e onboarding conflict exception
     * @param request current HTTP request
     * @return HTTP 409 response
     */
    @ExceptionHandler(MerchantOnboardingConflictException.class)
    public ResponseEntity<ApiErrorResponse> onboardingConflict(MerchantOnboardingConflictException e, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "MERCHANT_ONBOARDING_CONFLICT",
                safe(e.getMessage(), "Merchant onboarding conflict"), request, Map.of(), e, false);
    }

    /**
     * Translates database integrity violations into a stable conflict response.
     *
     * @param e data-integrity exception
     * @param request current HTTP request
     * @return sanitized HTTP 409 response
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> dataConflict(DataIntegrityViolationException e, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "DATA_CONFLICT",
                "The request conflicts with existing data", request, Map.of(), e, true);
    }

    /**
     * Handles caller-controlled invalid arguments that are safe to classify as bad requests.
     *
     * @param e invalid-argument exception
     * @param request current HTTP request
     * @return HTTP 400 response
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> badRequest(IllegalArgumentException e, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                safe(e.getMessage(), "Invalid request"), request, Map.of(), e, false);
    }

    /**
     * Distinguishes expected payment-state conflicts from unexpected internal state failures.
     *
     * @param e illegal-state exception
     * @param request current HTTP request
     * @return HTTP 409 for recognized business-state conflicts, otherwise sanitized HTTP 500
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiErrorResponse> illegalState(IllegalStateException e, HttpServletRequest request) {
        String message = safe(e.getMessage(), "Invalid operation state");
        if (isBusinessStateMessage(message)) {
            return response(HttpStatus.CONFLICT, "INVALID_PAYMENT_STATE", message, request, Map.of(), e, false);
        }
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected internal error occurred", request, Map.of(), e, true);
    }

    /**
     * Final exception boundary for failures not matched by a more specific handler.
     *
     * <p>The cause chain is inspected for known domain, validation, timeout, and upstream
     * availability failures before falling back to a sanitized {@code INTERNAL_ERROR}.
     *
     * @param e unhandled exception
     * @param request current HTTP request
     * @return normalized and sanitized API error response
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> unexpected(Exception e, HttpServletRequest request) {
        PaymentGatewayException domain = findCause(e, PaymentGatewayException.class);
        if (domain != null) return domainResponse(domain, request);

        IllegalArgumentException nestedInvalid = findCause(e, IllegalArgumentException.class);
        if (nestedInvalid != null) {
            return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                    safe(nestedInvalid.getMessage(), "Invalid request"), request, Map.of(), e, false);
        }
        IllegalStateException nestedState = findCause(e, IllegalStateException.class);
        if (nestedState != null && isBusinessStateMessage(nestedState.getMessage())) {
            return response(HttpStatus.CONFLICT, "INVALID_PAYMENT_STATE",
                    safe(nestedState.getMessage(), "The payment is not in a valid state for this operation"),
                    request, Map.of(), e, false);
        }
        DataIntegrityViolationException nestedData = findCause(e, DataIntegrityViolationException.class);
        if (nestedData != null) {
            return response(HttpStatus.CONFLICT, "DATA_CONFLICT",
                    "The request conflicts with existing data", request, Map.of(), e, true);
        }

        Throwable timeout = findByNameOrMessage(e, "timeout", "timed out", "sockettimeoutexception");
        if (timeout != null) {
            return response(HttpStatus.GATEWAY_TIMEOUT, "UPSTREAM_TIMEOUT",
                    "An upstream service did not respond in time", request, Map.of(), e, true);
        }

        Throwable unavailable = findByNameOrMessage(e,
                "rpcexception", "no provider available", "no servers available",
                "failed to invoke remote", "connection refused", "connection is closed");
        if (unavailable != null) {
            return response(HttpStatus.SERVICE_UNAVAILABLE, "UPSTREAM_UNAVAILABLE",
                    "A required upstream service is temporarily unavailable", request, Map.of(), e, true);
        }

        // Compatibility with older/remote nodes that may still serialize the former core-local exception names.
        Throwable oldNotFound = findBySimpleName(e, "PaymentNotFoundException");
        if (oldNotFound != null) {
            return response(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment was not found",
                    request, Map.of(), e, false);
        }
        Throwable oldIdempotency = findBySimpleName(e, "IdempotencyConflictException");
        if (oldIdempotency != null) {
            return response(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "Idempotency-Key was already used with a different request", request, Map.of(), e, false);
        }
        if (findBySimpleName(e, "InvalidPaymentStateException") != null) {
            return response(HttpStatus.CONFLICT, "INVALID_PAYMENT_STATE",
                    "The payment is not in a valid state for this operation", request, Map.of(), e, false);
        }
        if (findBySimpleName(e, "ProviderCapabilityException") != null) {
            return response(HttpStatus.UNPROCESSABLE_CONTENT, "UNSUPPORTED_PROVIDER_CAPABILITY",
                    "The selected provider does not support this operation", request, Map.of(), e, false);
        }
        if (findBySimpleName(e, "ProviderNotAvailableException") != null) {
            return response(HttpStatus.UNPROCESSABLE_CONTENT, "PROVIDER_NOT_AVAILABLE",
                    "The selected provider is not enabled or configured", request, Map.of(), e, false);
        }
        if (findBySimpleName(e, "ProviderUnavailableException") != null) {
            return response(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_UNAVAILABLE",
                    "The selected provider is temporarily unavailable", request, Map.of(), e, true);
        }
        if (findBySimpleName(e, "ProviderOperationRejectedException") != null) {
            return response(HttpStatus.UNPROCESSABLE_CONTENT, "PROVIDER_REJECTED",
                    "Provider rejected the requested operation", request, Map.of(), e, false);
        }

        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected internal error occurred", request, Map.of(), e, true);
    }

    /**
     * Maps a shared payment-domain exception to its HTTP status and safe response details.
     *
     * @param e shared payment-domain exception
     * @param request current HTTP request
     * @return normalized API error response
     */
    private ResponseEntity<ApiErrorResponse> domainResponse(PaymentGatewayException e, HttpServletRequest request) {
        HttpStatus status;
        Map<String, Object> details = new LinkedHashMap<>();

        if (e instanceof PaymentNotFoundException) {
            status = HttpStatus.NOT_FOUND;
        }
        else if (e instanceof DuplicateMerchantOrderException duplicate) {
            status = HttpStatus.CONFLICT;
            details.put("merchantOrderId", duplicate.getMerchantOrderId());
        }
        else if (e instanceof IdempotencyConflictException || e instanceof InvalidPaymentStateException) {
            status = HttpStatus.CONFLICT;
        }
        else if (e instanceof ProviderCapabilityException capability) {
            status = HttpStatus.UNPROCESSABLE_CONTENT;
            details.put("providerCode", capability.getProviderCode());
            details.put("capability", capability.getCapability().name());
        }
        else if (e instanceof ProviderNotAvailableException provider) {
            status = HttpStatus.UNPROCESSABLE_CONTENT;
            details.put("providerCode", provider.getProviderCode());
        }
        else if (e instanceof ProviderOperationRejectedException provider) {
            status = HttpStatus.UNPROCESSABLE_CONTENT;
            details.put("providerCode", provider.getProviderCode());
        }
        else if (e instanceof ProviderUnavailableException provider) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
            details.put("providerCode", provider.getProviderCode());
        }
        else {
            status = HttpStatus.BAD_REQUEST;
        }

        return response(status, e.getErrorCode(), safe(e.getMessage(), status.getReasonPhrase()), request,
                details, e, status.is5xxServerError());
    }

    /**
     * Builds the common API error envelope and logs the failure at the appropriate severity.
     *
     * @param status HTTP status to return
     * @param code stable machine-readable error code
     * @param message safe client-facing message
     * @param request current HTTP request
     * @param details optional structured error details
     * @param error original exception for server-side logging
     * @param internal whether the failure is internal or infrastructure-related
     * @return response entity containing the normalized error envelope
     */
    private ResponseEntity<ApiErrorResponse> response(HttpStatus status, String code, String message,
            HttpServletRequest request, Map<String, Object> details, Throwable error, boolean internal) {
        String correlationId = trimToNull(request.getHeader(CORRELATION_HEADER));
        String path = request.getRequestURI();
        if (internal) {
            log.error("API failure correlationId={} code={} status={} path={}",
                    correlationId, code, status.value(), path, error);
        }
        else {
            log.warn("API request rejected correlationId={} code={} status={} path={} message={}",
                    correlationId, code, status.value(), path, message);
        }
        ApiErrorResponse body = new ApiErrorResponse(
                Instant.now(), status.value(), status.getReasonPhrase(), code, message,
                path, correlationId, details == null ? Map.of() : Map.copyOf(details));
        return ResponseEntity.status(status).body(body);
    }

    /**
     * Sanitizes a message for client output and log metadata by removing line breaks and limiting length.
     *
     * @param value candidate message
     * @param fallback fallback text when the candidate is blank
     * @return sanitized message with a maximum length of 500 characters
     */
    private static String safe(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String cleaned = value.replace('\n', ' ').replace('\r', ' ').trim();
        return cleaned.length() <= 500 ? cleaned : cleaned.substring(0, 500);
    }

    /**
     * Detects legacy business-state messages that should map to {@code INVALID_PAYMENT_STATE}.
     *
     * @param message exception message to inspect
     * @return {@code true} when the message represents an expected payment-state conflict
     */
    private static boolean isBusinessStateMessage(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("cannot be voided")
                || lower.contains("can be refunded")
                || lower.contains("payment has no provider order id")
                || lower.contains("illegal payment state transition");
    }

    /**
     * Trims a string and converts blank values to {@code null}.
     *
     * @param value input value
     * @return trimmed value, or {@code null} when empty
     */
    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Finds the first cause in an exception chain assignable to the requested type.
     *
     * @param root root exception
     * @param type required exception type
     * @param <T> exception subtype
     * @return matching cause, or {@code null} when none exists
     */
    private static <T extends Throwable> T findCause(Throwable root, Class<T> type) {
        for (Throwable current = root; current != null; current = current.getCause()) {
            if (type.isInstance(current)) return type.cast(current);
        }
        return null;
    }

    /**
     * Finds a legacy or remote exception by simple class name or serialized name in its message.
     *
     * @param root root exception
     * @param simpleName simple exception class name to find
     * @return matching cause, or {@code null} when none exists
     */
    private static Throwable findBySimpleName(Throwable root, String simpleName) {
        for (Throwable current = root; current != null; current = current.getCause()) {
            if (current.getClass().getSimpleName().equals(simpleName)) return current;
            String message = current.getMessage();
            if (message != null && message.contains(simpleName)) return current;
        }
        return null;
    }

    /**
     * Searches the cause chain for infrastructure keywords in exception class names or messages.
     *
     * @param root root exception
     * @param needles case-insensitive keywords to search for
     * @return matching cause, or {@code null} when no keyword matches
     */
    private static Throwable findByNameOrMessage(Throwable root, String... needles) {
        for (Throwable current = root; current != null; current = current.getCause()) {
            String haystack = (current.getClass().getName() + " " + safe(current.getMessage(), "")).toLowerCase(Locale.ROOT);
            for (String needle : needles) {
                if (haystack.contains(needle.toLowerCase(Locale.ROOT))) return current;
            }
        }
        return null;
    }
}
