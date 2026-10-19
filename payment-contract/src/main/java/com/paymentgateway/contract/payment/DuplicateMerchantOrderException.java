package com.paymentgateway.contract.payment;

import com.paymentgateway.contract.error.PaymentGatewayException;

/**
 * Raised when a merchant attempts to create a new payment using a merchantOrderId
 * that is already owned by another payment for the same merchant.
 *
 * A retry of the original request should use the same Idempotency-Key; a genuinely
 * new payment must use a new merchantOrderId.
 */
public class DuplicateMerchantOrderException extends PaymentGatewayException {
    private final String merchantOrderId;

    /**
     * Creates a duplicate-order exception for a merchant-scoped order identifier.
     *
     * @param merchantOrderId merchant order identifier that already belongs to another payment
     */
    public DuplicateMerchantOrderException(String merchantOrderId) {
        super("DUPLICATE_MERCHANT_ORDER", message(merchantOrderId));
        this.merchantOrderId = merchantOrderId;
    }

    /**
     * Creates a duplicate-order exception and retains the underlying persistence cause.
     *
     * @param merchantOrderId merchant order identifier that already belongs to another payment
     * @param cause underlying persistence failure
     */
    public DuplicateMerchantOrderException(String merchantOrderId, Throwable cause) {
        super("DUPLICATE_MERCHANT_ORDER", message(merchantOrderId), cause);
        this.merchantOrderId = merchantOrderId;
    }

    /**
     * Returns the merchant-scoped order identifier that caused the conflict.
     *
     * @return duplicate merchant order identifier
     */
    public String getMerchantOrderId() {
        return merchantOrderId;
    }

    /**
     * Builds the safe conflict message used by API adapters.
     *
     * @param merchantOrderId duplicate merchant order identifier
     * @return safe remediation-oriented conflict message
     */
    private static String message(String merchantOrderId) {
        return "merchantOrderId '" + merchantOrderId
                + "' already exists for this merchant. Use a new merchantOrderId for a new payment, "
                + "or retry the original request with the same Idempotency-Key.";
    }
}
