package com.paymentgateway.core.persistence.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.paymentgateway.common.PaymentStatus;
import com.paymentgateway.contract.error.InvalidPaymentStateException;

/**
 * Verifies the behavior of payment entity state.
 */
class PaymentEntityStateTest {
	/**
	 * Executes the expiry uses domain state machine and late verified success is
	 * allowed operation.
	 */
	@Test
	void expiryUsesDomainStateMachineAndLateVerifiedSuccessIsAllowed() {
		PaymentEntity payment = PaymentEntity.initiated(UUID.randomUUID(), UUID.randomUUID(), "ORDER-1", 1000, "MMK");
		payment.markProviderRequested("ABANK_APLUS", "ORDER-PROVIDER-1");
		payment.applyInitiation("ORDER-PROVIDER-1", null, "DISPLAY_QR", "qr", null, null, PaymentStatus.QR_GENERATED);
		payment.configureCheckout("HOSTED_PAGE", Instant.now().plusSeconds(60));

		assertFalse(payment.expireIfDue(Instant.now()));
		assertTrue(payment.expireIfDue(Instant.now().plusSeconds(61)));
		assertEquals(PaymentStatus.EXPIRED, payment.getStatus());

		payment.applyVerifiedStatus(PaymentStatus.SUCCEEDED);
		assertEquals(PaymentStatus.SUCCEEDED, payment.getStatus());
	}

	/**
	 * Executes the terminal refund cannot be reopened operation.
	 */
	@Test
	void terminalRefundCannotBeReopened() {
		PaymentEntity payment = PaymentEntity.initiated(UUID.randomUUID(), UUID.randomUUID(), "ORDER-2", 1000, "MMK");
		payment.markProviderRequested("ABANK_APLUS", "ORDER-PROVIDER-2");
		payment.applyVerifiedStatus(PaymentStatus.REFUNDED);
		assertThrows(InvalidPaymentStateException.class, () -> payment.applyVerifiedStatus(PaymentStatus.SUCCEEDED));
	}
}
