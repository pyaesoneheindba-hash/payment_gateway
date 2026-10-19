package com.paymentgateway.contract.provider;

import com.paymentgateway.common.ProviderOutcome;
import java.io.Serializable;

/**
 * Represents the result of enquire payment processing.
 */
public record EnquirePaymentResult(
        ProviderOutcome outcome,
        String providerOrderId,
        String providerReferenceId,
        Integer providerTransactionStatus,
        String providerStatusDescription,
        String transactionId,
        String billNo,
        String endToEndId,
        String transactionDateTime,
        String institutionName,
        String responseMessage
) implements Serializable {}
