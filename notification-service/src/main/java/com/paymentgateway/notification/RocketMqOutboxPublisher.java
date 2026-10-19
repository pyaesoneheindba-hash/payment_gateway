package com.paymentgateway.notification;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * Transactional-outbox relay. Each committed lifecycle event first emits a best-effort
 * Redis portal-live notification, then continues through the durable RocketMQ path.
 * A crash after RocketMQ send but before PUBLISHED may duplicate a message; inbox_event
 * makes the durable consumer idempotent by eventId. Portal refreshes are idempotent too.
 */
@Component
@ConditionalOnProperty(prefix = "payment.rocketmq", name = "enabled", havingValue = "true")
public class RocketMqOutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(RocketMqOutboxPublisher.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RocketMqProperties properties;
    private final RocketMqClientManager rocketMq;
    private final ObjectProvider<PortalLiveEventPublisher> livePublisher;

    /**
     * Creates a new {@code RocketMqOutboxPublisher} instance.
     *
     * @param jdbc the jdbc value
     * @param mapper the object mapper used to serialize and deserialize JSON
     * @param properties the properties value
     * @param rocketMq the rocket mq value
     * @param livePublisher the live publisher value
     */
    public RocketMqOutboxPublisher(JdbcTemplate jdbc, ObjectMapper mapper,
            RocketMqProperties properties, RocketMqClientManager rocketMq,
            ObjectProvider<PortalLiveEventPublisher> livePublisher) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.properties = properties;
        this.rocketMq = rocketMq;
        this.livePublisher = livePublisher;
    }

    /**
     * Executes the publish operation.
     */
    @Scheduled(fixedDelayString = "${payment.rocketmq.outbox-poll-ms:500}")
    public void publish() {
        recoverStaleClaims();
        for (ClaimedEvent event : claimBatch()) {
            try {
                PaymentLifecycleMessage envelope = new PaymentLifecycleMessage(
                        event.eventId(), event.aggregateType(), event.aggregateId(), event.eventType(),
                        event.eventVersion(), event.occurredAt(), mapper.readValue(event.payloadJson(), Map.class));
                // Portal SSE is a best-effort notification path, not the source of truth. Publish
                // directly from the committed outbox relay before RocketMQ so the open portal can
                // refresh authoritative REST data immediately and does not depend on a consumer
                // round trip. PortalLiveEventPublisher intentionally swallows Redis failures.
                PortalLiveEventPublisher publisher = livePublisher.getIfAvailable();
                if (publisher != null) publisher.publish(envelope);

                byte[] body = mapper.writeValueAsBytes(envelope);
                rocketMq.send(event.topic(), properties.getPaymentStatusTag(), event.eventId().toString(), body);
                jdbc.update("""
                    UPDATE outbox_event SET status='PUBLISHED',published_at=now(),last_publish_error=NULL,
                        version=version+1,updated_at=now()
                    WHERE event_id=? AND status='CLAIMED'
                    """, event.eventId());
            }
            catch (Exception e) {
                String error = truncate(e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()), 4000);
                if (event.publishAttemptCount() >= Math.max(1, properties.getMaxPublishAttempts())) {
                    log.error("RocketMQ outbox event {} reached max publish attempts", event.eventId(), e);
                    jdbc.update("""
                        UPDATE outbox_event SET status='DEAD',last_publish_error=?,version=version+1,updated_at=now()
                        WHERE event_id=? AND status='CLAIMED'
                        """, error, event.eventId());
                }
                else {
                    log.warn("RocketMQ publish failed for {}; returning outbox row to NEW", event.eventId(), e);
                    jdbc.update("""
                        UPDATE outbox_event SET status='NEW',last_publish_error=?,
                            next_publish_at=now() + (? * interval '1 second'),
                            version=version+1,updated_at=now()
                        WHERE event_id=? AND status='CLAIMED'
                        """, error, retryDelaySeconds(event.publishAttemptCount()), event.eventId());
                }
            }
        }
    }

    /**
     * Executes the claim batch operation.
     *
     * @return the matching values
     */
    private List<ClaimedEvent> claimBatch() {
        return jdbc.query("""
            WITH picked AS (
                SELECT event_id FROM outbox_event
                WHERE status='NEW' AND event_type IN ('PAYMENT_STATUS_CHANGED','PAYMENT_CREATED') AND next_publish_at <= now()
                ORDER BY created_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            UPDATE outbox_event o
            SET status='CLAIMED',publish_attempt_count=o.publish_attempt_count+1,
                version=o.version+1,updated_at=now()
            FROM picked
            WHERE o.event_id=picked.event_id
            RETURNING o.event_id,o.aggregate_type,o.aggregate_id,o.event_type,o.event_version,o.topic,
                      o.payload::text,o.created_at,o.publish_attempt_count
            """, (rs, rowNum) -> new ClaimedEvent(
                    rs.getObject("event_id", UUID.class),
                    rs.getString("aggregate_type"),
                    rs.getObject("aggregate_id", UUID.class),
                    rs.getString("event_type"),
                    rs.getInt("event_version"),
                    rs.getString("topic"),
                    rs.getString("payload"),
                    toInstant(rs.getObject("created_at")),
                    rs.getInt("publish_attempt_count")),
                Math.max(1, properties.getOutboxBatchSize()));
    }

    /**
     * Executes the recover stale claims operation.
     */
    private void recoverStaleClaims() {
        long seconds = Math.max(30, properties.getStaleClaimSeconds());
        jdbc.update("""
            UPDATE outbox_event
            SET status='NEW',last_publish_error='Recovered stale RocketMQ publish claim',next_publish_at=now(),
                version=version+1,updated_at=now()
            WHERE status='CLAIMED' AND updated_at < now() - (? * interval '1 second')
            """, seconds);
    }

    /**
     * Executes the retry delay seconds operation.
     *
     * @param attempt the attempt value
     * @return the resulting retry delay seconds value
     */
    private long retryDelaySeconds(int attempt) {
        long base = Math.max(1, properties.getPublishRetryBaseSeconds());
        long max = Math.max(base, properties.getPublishRetryMaxSeconds());
        int exponent = Math.max(0, Math.min(attempt - 1, 20));
        long multiplier = 1L << exponent;
        if (base > Long.MAX_VALUE / multiplier) return max;
        return Math.min(max, base * multiplier);
    }

    /**
     * Converts the current value to instant.
     *
     * @param value the value to process
     * @return the resulting {@code Instant} value
     */
    private static Instant toInstant(Object value) {
        if (value instanceof Instant i) return i;
        if (value instanceof OffsetDateTime o) return o.toInstant();
        if (value instanceof Timestamp t) return t.toInstant();

        throw new IllegalArgumentException(
                "Unsupported timestamp type: "
                        + (value == null ? "null" : value.getClass().getName()));
    }

    /**
     * Executes the truncate operation.
     *
     * @param value the value to process
     * @param max the max value
     * @return the resulting truncate text
     */
    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }

    /**
     * Represents a claimed event exchanged by the platform.
     */
    private record ClaimedEvent(UUID eventId, String aggregateType, UUID aggregateId,
            String eventType, int eventVersion, String topic, String payloadJson,
            Instant occurredAt, int publishAttemptCount) {}
}
