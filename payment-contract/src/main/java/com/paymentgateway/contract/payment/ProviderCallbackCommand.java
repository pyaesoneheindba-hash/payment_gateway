package com.paymentgateway.contract.payment;

import java.io.Serializable;

/**
 * Carries command data for provider callback processing.
 */
public record ProviderCallbackCommand(
        String providerCode,
        String callbackType,
        String orderId,
        String providerReferenceId,
        String amount,
        String status,
        String responseCode,
        String transactionId,
        String approvalCode,
        String billNo,
        String endToEndId,
        String transactionDateTime,
        String institutionName,
        String maskedPan,
        String errorCode,
        String errorDescription,
        Boolean signatureVerified,
        String dedupeKey,
        String rawPayloadJson
) implements Serializable {}
