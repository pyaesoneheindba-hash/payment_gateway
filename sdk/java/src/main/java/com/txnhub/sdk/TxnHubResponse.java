package com.txnhub.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpHeaders;

/**
 * Carries response data for txn hub operations.
 */
public record TxnHubResponse(int statusCode, String body, HttpHeaders headers) {
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Executes the JSON operation.
     *
     * @param type the type value
     * @return the resulting {@code T} value
     */
    public <T> T json(Class<T> type) {
        try { return JSON.readValue(body, type); }
        catch (Exception e) { throw new TxnHubException("Could not parse TXN HUB JSON response", e); }
    }
}
