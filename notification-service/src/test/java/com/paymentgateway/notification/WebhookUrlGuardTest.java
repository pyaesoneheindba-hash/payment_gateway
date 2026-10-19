package com.paymentgateway.notification;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Verifies the behavior of webhook URL guard.
 */
class WebhookUrlGuardTest {
    /**
     * Executes the rejects loopback and private addresses at delivery time operation.
     */
    @Test
    void rejectsLoopbackAndPrivateAddressesAtDeliveryTime() {
        WebhookNotificationProperties properties = new WebhookNotificationProperties();
        WebhookUrlGuard guard = new WebhookUrlGuard(properties);
        assertThrows(IllegalArgumentException.class, () -> guard.validate("https://127.0.0.1/hook"));
        assertThrows(IllegalArgumentException.class, () -> guard.validate("https://10.0.0.10/hook"));
        assertThrows(IllegalArgumentException.class, () -> guard.validate("https://192.168.1.10/hook"));
    }
}
