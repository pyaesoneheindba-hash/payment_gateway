package com.paymentgateway.audit.util;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Implements audit JSON behavior for the payment gateway platform.
 */
public final class AuditJson {
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /**
     * Creates a new {@code AuditJson} instance.
     */
    private AuditJson() {}

    /**
     * Executes the write operation.
     *
     * @param value the value to process
     * @return the resulting write text
     */
    public static String write(Object value) {
        if (value == null) return null;
        try {
            return MAPPER.writeValueAsString(value);
        }
        catch (Exception e) {
            return "{\"serializationError\":\"" + safe(e.getMessage()) + "\"}";
        }
    }

    /**
     * Executes the read operation.
     *
     * @param json the JSON value
     * @param type the type value
     * @return the resulting {@code T} value
     */
    public static <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, type);
        }
        catch (Exception e) {
            throw new IllegalArgumentException("Cannot parse provider JSON response", e);
        }
    }

    /**
     * Executes the safe operation.
     *
     * @param s the s value
     * @return the resulting safe text
     */
    private static String safe(String s) {
        return s == null ? "unknown" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
