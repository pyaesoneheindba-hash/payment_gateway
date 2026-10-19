package com.paymentgateway.provider.ayapay;

import com.paymentgateway.common.ProviderOutcome;
import java.util.Locale;

/**
 * Maps aya pay status values between internal and external representations.
 */
public final class AyaPayStatusMapper {
    /**
     * Creates a new {@code AyaPayStatusMapper} instance.
     */
    private AyaPayStatusMapper() {}
    /**
     * Executes the map operation.
     *
     * @param status the payment or delivery status
     * @return the resulting {@code ProviderOutcome} value
     */
    public static ProviderOutcome map(String status) {
        if(status==null||status.isBlank()) return ProviderOutcome.UNKNOWN;
        return switch(status.trim().toLowerCase(Locale.ROOT)) {
            case "success", "done", "approved", "completed" -> ProviderOutcome.SUCCESS;
            case "pending", "processing", "requested" -> ProviderOutcome.PENDING;
            case "refunded", "refund" -> ProviderOutcome.REFUNDED;
            case "failed", "fail", "declined", "rejected", "expired" -> ProviderOutcome.REJECTED;
            default -> ProviderOutcome.UNKNOWN;
        };
    }
}
