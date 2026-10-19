package com.txnhub.sdk;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * Verifies the behavior of txn hub exception.
 */
class TxnHubExceptionTest {
    /**
     * Executes the parses structured API error operation.
     */
    @Test
    void parsesStructuredApiError() {
        TxnHubException e = new TxnHubException(409, """
                {"code":"IDEMPOTENCY_CONFLICT","message":"Idempotency-Key was already used with a different request","correlationId":"corr-1"}
                """);
        assertEquals(409, e.statusCode());
        assertEquals("IDEMPOTENCY_CONFLICT", e.errorCode());
        assertEquals("corr-1", e.correlationId());
        assertTrue(e.getMessage().contains("IDEMPOTENCY_CONFLICT"));
    }
}
