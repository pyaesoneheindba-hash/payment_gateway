package com.paymentgateway.api.web;

import com.paymentgateway.common.PaymentStatus;
import java.util.UUID;

/**
 * Carries response data for payment status operations.
 */
public record PaymentStatusResponse(
        UUID paymentId,
        String merchantOrderId,
        PaymentStatus status,
        String providerCode,
        String providerOrderId,
        String providerReferenceId
) {}
