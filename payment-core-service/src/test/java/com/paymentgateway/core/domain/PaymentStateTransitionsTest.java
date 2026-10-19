package com.paymentgateway.core.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.paymentgateway.common.PaymentStatus;
import org.junit.jupiter.api.Test;

/**
 * Verifies the behavior of payment state transitions.
 */
class PaymentStateTransitionsTest {
    /**
     * Executes the provider verified success may recover locally expired payment operation.
     */
    @Test
    void providerVerifiedSuccessMayRecoverLocallyExpiredPayment() {
        assertTrue(PaymentStateTransitions.isAllowed(PaymentStatus.EXPIRED, PaymentStatus.SUCCEEDED));
        assertDoesNotThrow(() -> PaymentStateTransitions.requireAllowed(PaymentStatus.EXPIRED, PaymentStatus.SUCCEEDED));
    }

    /**
     * Executes the terminal refund cannot return to succeeded operation.
     */
    @Test
    void terminalRefundCannotReturnToSucceeded() {
        assertThrows(IllegalStateException.class,
                () -> PaymentStateTransitions.requireAllowed(PaymentStatus.REFUNDED, PaymentStatus.SUCCEEDED));
    }

    /**
     * Executes the pending may become succeeded operation.
     */
    @Test
    void pendingMayBecomeSucceeded() {
        assertDoesNotThrow(() -> PaymentStateTransitions.requireAllowed(PaymentStatus.PENDING, PaymentStatus.SUCCEEDED));
    }
}
