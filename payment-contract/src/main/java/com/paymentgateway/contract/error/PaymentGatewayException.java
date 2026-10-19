package com.paymentgateway.contract.error;

/**
 * Base exception for stable business/domain failures that may cross the Dubbo boundary.
 * The error code is transport-neutral; the HTTP API maps it to an appropriate status.
 */
public abstract class PaymentGatewayException extends RuntimeException {
    private final String errorCode;

    /**
     * Creates a domain exception with a stable machine-readable error code.
     *
     * @param errorCode stable transport-neutral error code
     * @param message safe human-readable domain message
     */
    protected PaymentGatewayException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * Creates a domain exception with a stable error code and an underlying cause.
     *
     * @param errorCode stable transport-neutral error code
     * @param message safe human-readable domain message
     * @param cause underlying failure for server-side diagnostics
     */
    protected PaymentGatewayException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * Returns the stable machine-readable error code used by external adapters.
     *
     * @return error code such as {@code PAYMENT_NOT_FOUND} or {@code PROVIDER_UNAVAILABLE}
     */
    public String getErrorCode() {
        return errorCode;
    }
}
