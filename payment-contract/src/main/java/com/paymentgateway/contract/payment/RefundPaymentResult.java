package com.paymentgateway.contract.payment;

import com.paymentgateway.common.RefundStatus;
import java.io.Serializable;
import java.util.UUID;

/**
 * Represents the result of refund payment processing.
 */
public record RefundPaymentResult(
        UUID refundId,
        UUID paymentId,
        String merchantRefundId,
        long amountMinor,
        String currency,
        RefundStatus status,
        String providerCode,
        String providerRefundReferenceId,
        String message
) implements Serializable {}
