package com.paymentgateway.api.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Verifies the behavior of webhook URL policy.
 */
class WebhookUrlPolicyTest {
    private final WebhookUrlPolicy policy = new WebhookUrlPolicy(false, false);

    /**
     * Executes the accepts and normalizes public HTTPS URL operation.
     */
    @Test
    void acceptsAndNormalizesPublicHttpsUrl() {
        assertEquals("https://merchant.example.com/webhooks/payment",
                policy.validateAndNormalize("https://merchant.example.com/a/../webhooks/payment"));
    }

    /**
     * Executes the rejects HTTP and private targets operation.
     */
    @Test
    void rejectsHttpAndPrivateTargets() {
        assertThrows(IllegalArgumentException.class,
                () -> policy.validateAndNormalize("http://merchant.example.com/webhook"));
        assertThrows(IllegalArgumentException.class,
                () -> policy.validateAndNormalize("https://127.0.0.1/webhook"));
        assertThrows(IllegalArgumentException.class,
                () -> policy.validateAndNormalize("https://192.168.1.10/webhook"));
    }

    /**
     * Executes the rejects user info and fragments operation.
     */
    @Test
    void rejectsUserInfoAndFragments() {
        assertThrows(IllegalArgumentException.class,
                () -> policy.validateAndNormalize("https://user:pass@merchant.example.com/webhook"));
        assertThrows(IllegalArgumentException.class,
                () -> policy.validateAndNormalize("https://merchant.example.com/webhook#fragment"));
    }
}
