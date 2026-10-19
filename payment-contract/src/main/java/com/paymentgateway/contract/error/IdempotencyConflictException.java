package com.paymentgateway.contract.error;

/**
 * Raised when an Idempotency-Key is reused with a different request payload.
 */
public final class IdempotencyConflictException extends PaymentGatewayException {
	private static final long serialVersionUID = 4413864321382749820L;

	/**
	 * Creates the standard idempotency-conflict exception.
	 */
	public IdempotencyConflictException() {
		super("IDEMPOTENCY_CONFLICT", "Idempotency-Key was already used with a different request");
	}
}
