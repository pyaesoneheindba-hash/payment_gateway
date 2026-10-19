package com.paymentgateway.notification;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Versioned event envelope carried by RocketMQ. */
public record PaymentLifecycleMessage(
        UUID eventId,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        Map<String, Object> payload) {}
