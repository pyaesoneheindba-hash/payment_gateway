package com.paymentgateway.common;

/**
 * Defines the supported provider outcome values.
 */
public enum ProviderOutcome {
    SUCCESS,
    PENDING,
    REJECTED,
    UNKNOWN,
    REFUNDED,
    VOIDED,
    NOT_FOUND
}
