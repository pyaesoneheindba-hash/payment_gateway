package com.paymentgateway.contract.error;

/** Raised when a requested payment cannot be found. */
public final class PaymentNotFoundException extends PaymentGatewayException {
    /**
     * Creates a payment-not-found exception for the supplied identifier.
     *
     * @param paymentId payment identifier requested by the caller
     */
    public PaymentNotFoundException(Object paymentId) {
        super("PAYMENT_NOT_FOUND", "Payment was not found: " + paymentId);
    }
}
