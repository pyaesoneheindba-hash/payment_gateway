package com.paymentgateway.core.domain;

import com.paymentgateway.common.PaymentStatus;
import com.paymentgateway.contract.error.InvalidPaymentStateException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Central authoritative payment state machine. */
public final class PaymentStateTransitions {
    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED = new EnumMap<>(PaymentStatus.class);

    static {
        allow(PaymentStatus.INITIATED, PaymentStatus.PROVIDER_REQUESTED, PaymentStatus.FAILED, PaymentStatus.EXPIRED);
        allow(PaymentStatus.PROVIDER_REQUESTED, PaymentStatus.QR_REQUESTED, PaymentStatus.QR_GENERATED,
                PaymentStatus.ACTION_REQUIRED, PaymentStatus.PENDING, PaymentStatus.UNKNOWN,
                PaymentStatus.FAILED, PaymentStatus.VOIDED, PaymentStatus.REFUNDED, PaymentStatus.EXPIRED);
        allow(PaymentStatus.QR_REQUESTED, PaymentStatus.QR_GENERATED, PaymentStatus.ACTION_REQUIRED,
                PaymentStatus.PENDING, PaymentStatus.UNKNOWN, PaymentStatus.FAILED, PaymentStatus.EXPIRED);
        allow(PaymentStatus.QR_GENERATED, PaymentStatus.PENDING, PaymentStatus.UNKNOWN,
                PaymentStatus.SUCCEEDED, PaymentStatus.FAILED, PaymentStatus.VOIDED, PaymentStatus.EXPIRED);
        allow(PaymentStatus.ACTION_REQUIRED, PaymentStatus.PENDING, PaymentStatus.UNKNOWN,
                PaymentStatus.SUCCEEDED, PaymentStatus.FAILED, PaymentStatus.VOIDED, PaymentStatus.EXPIRED);
        allow(PaymentStatus.PENDING, PaymentStatus.UNKNOWN, PaymentStatus.SUCCEEDED,
                PaymentStatus.FAILED, PaymentStatus.VOIDED, PaymentStatus.EXPIRED);
        allow(PaymentStatus.UNKNOWN, PaymentStatus.PENDING, PaymentStatus.SUCCEEDED,
                PaymentStatus.FAILED, PaymentStatus.VOIDED, PaymentStatus.EXPIRED);
        // Local expiry is not financial truth. A later independently verified provider success may override it.
        allow(PaymentStatus.EXPIRED, PaymentStatus.SUCCEEDED, PaymentStatus.FAILED);
        allow(PaymentStatus.SUCCEEDED, PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED, PaymentStatus.VOIDED);
        allow(PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED);
        allow(PaymentStatus.REFUNDED);
        allow(PaymentStatus.VOIDED);
        allow(PaymentStatus.FAILED);
    }

    /** Prevents instantiation of this state-machine utility. */
    private PaymentStateTransitions() {}

    /**
     * Registers allowed target states for one source state.
     *
     * @param from source payment status
     * @param to allowed target statuses
     */
    private static void allow(PaymentStatus from, PaymentStatus... to) {
        ALLOWED.put(from, to.length == 0 ? EnumSet.noneOf(PaymentStatus.class) : EnumSet.of(to[0], to));
    }

    /**
     * Checks whether a payment state transition is allowed by the authoritative state machine.
     *
     * @param from current payment status
     * @param to requested target status
     * @return {@code true} when the transition is allowed or is an idempotent same-state transition
     */
    public static boolean isAllowed(PaymentStatus from, PaymentStatus to) {
        if (from == null || to == null) return false;
        if (from == to) return true;
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * Validates a payment state transition and raises a domain exception when it is illegal.
     *
     * @param from current payment status
     * @param to requested target status
     * @throws InvalidPaymentStateException when the transition is not allowed
     */
    public static void requireAllowed(PaymentStatus from, PaymentStatus to) {
        if (!isAllowed(from, to)) {
            throw new InvalidPaymentStateException("Illegal payment state transition: " + from + " -> " + to);
        }
    }
}
