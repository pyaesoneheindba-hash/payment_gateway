package com.paymentgateway.contract.error;

/** Raised when a requested payment cannot be found. */
public final class PaymentNotFoundException extends PaymentGatewayException {
	private static final long serialVersionUID = 8089920052930048489L;

	/**
	 * Creates a payment-not-found exception for the supplied identifier.
	 *
	 * @param paymentId payment identifier requested by the caller
	 */
	public PaymentNotFoundException(Object paymentId) {
		super("PAYMENT_NOT_FOUND", "Payment was not found: " + paymentId);
	}
}
