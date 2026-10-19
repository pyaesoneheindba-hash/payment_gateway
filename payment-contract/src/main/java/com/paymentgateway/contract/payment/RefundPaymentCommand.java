package com.paymentgateway.contract.payment;

import java.io.Serializable;
import java.util.Map;
import java.util.UUID;

/**
 * Carries command data for refund payment processing.
 */
public record RefundPaymentCommand(
        UUID merchantId,
        UUID paymentId,
        String merchantRefundId,
        long amountMinor,
        Map<String,String> providerOptions,
        String idempotencyKey,
        String requestHash
) implements Serializable {}
