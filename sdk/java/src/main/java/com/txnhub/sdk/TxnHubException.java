package com.txnhub.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Exception thrown for TXN HUB HTTP/API failures. */
public class TxnHubException extends RuntimeException {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final int statusCode;
    private final String responseBody;
    private final String errorCode;
    private final String correlationId;
    private final String apiMessage;

    /**
     * Creates an SDK exception from an HTTP status and raw TXN HUB response body.
     *
     * @param statusCode HTTP status returned by TXN HUB
     * @param responseBody raw response body returned by TXN HUB
     */
    public TxnHubException(int statusCode, String responseBody) {
        this(statusCode, responseBody, parse(responseBody));
    }

    /**
     * Creates an SDK exception from an already parsed API error response.
     *
     * @param statusCode HTTP status returned by TXN HUB
     * @param responseBody raw response body returned by TXN HUB
     * @param parsed parsed error fields extracted from the response body
     */
    private TxnHubException(int statusCode, String responseBody, ParsedError parsed) {
        super(buildMessage(statusCode, parsed, responseBody));
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.errorCode = parsed.code();
        this.correlationId = parsed.correlationId();
        this.apiMessage = parsed.message();
    }

    /**
     * Creates an SDK exception for a local transport or signing failure.
     *
     * @param message local failure description
     * @param cause underlying failure
     */
    public TxnHubException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
        this.responseBody = null;
        this.errorCode = null;
        this.correlationId = null;
        this.apiMessage = null;
    }

    /** @return HTTP status returned by TXN HUB, or {@code 0} for a local SDK failure */
    public int statusCode() {
        return statusCode;
    }

    /** @return raw response body returned by TXN HUB, or {@code null} when unavailable */
    public String responseBody() {
        return responseBody;
    }

    /** @return stable TXN HUB API error code, or {@code null} when the body could not be parsed */
    public String errorCode() {
        return errorCode;
    }

    /** @return correlation identifier returned by TXN HUB, or {@code null} when absent */
    public String correlationId() {
        return correlationId;
    }

    /** @return safe API error message returned by TXN HUB, or {@code null} when absent */
    public String apiMessage() {
        return apiMessage;
    }

    /**
     * Parses the structured TXN HUB error envelope without failing the original exception construction.
     *
     * @param body raw HTTP response body
     * @return parsed error fields; all fields are null when parsing fails
     */
    private static ParsedError parse(String body) {
        if (body == null || body.isBlank()) return new ParsedError(null, null, null);
        try {
            JsonNode root = JSON.readTree(body);
            return new ParsedError(text(root, "code", text(root, "error", null)),
                    text(root, "message", null), text(root, "correlationId", null));
        }
        catch (Exception ignored) {
            return new ParsedError(null, null, null);
        }
    }

    /**
     * Reads a non-blank textual field from a parsed JSON object.
     *
     * @param root parsed JSON object
     * @param field field name to read
     * @param fallback fallback value when the field is absent or blank
     * @return field value or fallback
     */
    private static String text(JsonNode root, String field, String fallback) {
        if (root == null || !root.hasNonNull(field)) return fallback;
        String value = root.get(field).asText();
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * Builds the exception message used by SDK callers and logs.
     *
     * @param status HTTP status returned by TXN HUB
     * @param parsed structured error fields
     * @param raw raw HTTP response body
     * @return human-readable exception message
     */
    private static String buildMessage(int status, ParsedError parsed, String raw) {
        StringBuilder message = new StringBuilder("TXN HUB request failed with HTTP ").append(status);
        if (parsed.code() != null) message.append(" [").append(parsed.code()).append(']');
        if (parsed.message() != null) message.append(": ").append(parsed.message());
        else if (raw != null && !raw.isBlank()) message.append(": ").append(raw);
        if (parsed.correlationId() != null) message.append(" (correlationId=").append(parsed.correlationId()).append(')');
        return message.toString();
    }

    /** Parsed fields from the common TXN HUB error envelope. */
    private record ParsedError(String code, String message, String correlationId) {}
}
