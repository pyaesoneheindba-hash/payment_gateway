package com.paymentgateway.common;

/**
 * Defines the supported provider capability values.
 */
public enum ProviderCapability {
    QR_PAYMENT,
    MMQR_PAYMENT,
    HOSTED_PAYMENT,
    PUSH_PAYMENT,
    CUSTOMER_PRESENT_QR,
    ENQUIRY,
    CALLBACK,
    REFUND,
    VOID
}
