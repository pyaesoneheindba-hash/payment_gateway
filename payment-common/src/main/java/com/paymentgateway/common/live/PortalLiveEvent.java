package com.paymentgateway.common.live;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Sanitized live portal event broadcast after a committed payment lifecycle event.
 * Merchant portals are filtered server-side by merchantId; admin portals may receive all merchants.
 */
public record PortalLiveEvent(
        UUID eventId,
        String type,
        UUID merchantId,
        UUID paymentId,
        Instant occurredAt,
        Map<String, Object> data) {
}
