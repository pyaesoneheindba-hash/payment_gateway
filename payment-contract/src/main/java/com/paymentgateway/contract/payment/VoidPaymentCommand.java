package com.paymentgateway.contract.payment;

import java.io.Serializable;
import java.util.Map;
import java.util.UUID;

/**
 * Carries command data for void payment processing.
 */
public record VoidPaymentCommand(
        UUID merchantId,
        UUID paymentId,
        Map<String,String> providerOptions,
        String idempotencyKey,
        String requestHash
) implements Serializable {}
