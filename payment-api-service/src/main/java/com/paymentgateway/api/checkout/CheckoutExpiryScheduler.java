package com.paymentgateway.api.checkout;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Implements checkout expiry scheduler behavior for the payment gateway
 * platform.
 */
@Component
public class CheckoutExpiryScheduler {
	private final CheckoutSessionService sessions;

	/**
	 * Creates a new {@code CheckoutExpiryScheduler} instance.
	 *
	 * @param sessions the sessions value
	 */
	public CheckoutExpiryScheduler(CheckoutSessionService sessions) {
		this.sessions = sessions;
	}

	/**
	 * Executes the expire operation.
	 */
	@Scheduled(fixedDelayString = "${payment.checkout.expiry-scan-ms:5000}")
	public void expire() {
		sessions.expireDueSessions();
	}
}
