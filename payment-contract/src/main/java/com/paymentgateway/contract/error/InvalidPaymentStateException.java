package com.paymentgateway.contract.error;

/** Raised when an operation is not valid for the payment's current state. */
public final class InvalidPaymentStateException extends PaymentGatewayException {
    /**
     * Creates an invalid-state exception with a safe domain message.
     *
     * @param message explanation of the invalid payment-state operation
     */
    public InvalidPaymentStateException(String message) {
        super("INVALID_PAYMENT_STATE", message);
    }
}
