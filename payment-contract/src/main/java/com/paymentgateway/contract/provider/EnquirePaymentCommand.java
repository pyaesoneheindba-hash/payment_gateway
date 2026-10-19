package com.paymentgateway.contract.provider;

import java.io.Serializable;
import java.util.UUID;

/**
 * Carries command data for enquire payment processing.
 */
public record EnquirePaymentCommand(
        UUID paymentId,
        String providerOrderId,
        String providerReferenceId,
        String providerOrderDate,
        String providerNextAction
) implements Serializable {}
