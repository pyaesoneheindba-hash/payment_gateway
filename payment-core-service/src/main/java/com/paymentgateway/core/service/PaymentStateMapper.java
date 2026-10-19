package com.paymentgateway.core.service;

import com.paymentgateway.common.PaymentStatus;
import com.paymentgateway.common.ProviderOutcome;

/**
 * Maps payment state values between internal and external representations.
 */
public final class PaymentStateMapper {
    /**
     * Creates a new {@code PaymentStateMapper} instance.
     */
    private PaymentStateMapper() {}
    /**
     * Creates a value from provider.
     *
     * @param outcome the outcome value
     * @return the resulting {@code PaymentStatus} value
     */
    public static PaymentStatus fromProvider(ProviderOutcome outcome) {
        return switch (outcome) {
            case SUCCESS -> PaymentStatus.SUCCEEDED;
            case PENDING -> PaymentStatus.PENDING;
            case REFUNDED -> PaymentStatus.REFUNDED;
            case VOIDED -> PaymentStatus.VOIDED;
            case REJECTED -> PaymentStatus.FAILED;
            case UNKNOWN, NOT_FOUND -> PaymentStatus.UNKNOWN;
        };
    }
}
