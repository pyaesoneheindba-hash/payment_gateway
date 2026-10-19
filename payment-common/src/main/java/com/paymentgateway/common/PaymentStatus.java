package com.paymentgateway.common;

/**
 * Defines the supported payment status values.
 */
public enum PaymentStatus {
    INITIATED,
    PROVIDER_REQUESTED,
    QR_REQUESTED,
    QR_GENERATED,
    ACTION_REQUIRED,
    PENDING,
    UNKNOWN,
    SUCCEEDED,
    PARTIALLY_REFUNDED,
    REFUNDED,
    VOIDED,
    FAILED,
    EXPIRED
}
