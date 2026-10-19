package com.paymentgateway.contract.payment;

import com.paymentgateway.common.PaymentStatus;
import java.io.Serializable;
import java.util.UUID;

/**
 * Immutable data carrier for callback acceptance.
 */
public record CallbackAcceptance(
        boolean accepted,
        boolean duplicate,
        UUID paymentId,
        PaymentStatus paymentStatus,
        String message
) implements Serializable {}
