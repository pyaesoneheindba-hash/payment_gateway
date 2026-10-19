package com.paymentgateway.notification;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent RocketMQ consumer-side transaction. Inbox reservation and webhook
 * delivery fan-out commit atomically; redelivery after commit is a no-op.
 */
@Component
public class PaymentStatusEventHandler {
	private static final String CONSUMER_NAME = "notification-payment-status-v1";
	private static final String EVENT_TYPE = "PAYMENT_STATUS_CHANGED";
	private final JdbcTemplate jdbc;

	/**
	 * Creates a new {@code PaymentStatusEventHandler} instance.
	 *
	 * @param jdbc the jdbc value
	 */
	public PaymentStatusEventHandler(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Executes the handle operation.
	 *
	 * @param event             the event to process
	 * @param rocketMqMessageId the rocket mq message identifier value
	 * @return {@code true} when the condition is satisfied; otherwise {@code false}
	 */
	@Transactional
	public boolean handle(PaymentLifecycleMessage event, String rocketMqMessageId) {
		if (event == null || event.eventId() == null || event.aggregateId() == null) {
			throw new IllegalArgumentException("RocketMQ payment event is missing identity fields");
		}
		if (!EVENT_TYPE.equals(event.eventType())) {
			throw new IllegalArgumentException("Unexpected event type: " + event.eventType());
		}

		int inserted = jdbc.update("""
				INSERT INTO inbox_event(consumer_name,event_id,message_id,event_type,received_at,processed_at)
				VALUES (?,?,?,?,now(),NULL)
				ON CONFLICT(consumer_name,event_id) DO NOTHING
				""", CONSUMER_NAME, event.eventId(), rocketMqMessageId, event.eventType());
		if (inserted == 0)
			return false;

		Map<String, Object> payload = event.payload();
		if (payload == null || payload.get("merchantId") == null) {
			throw new IllegalArgumentException("Payment status event has no merchantId");
		}
		UUID merchantId = UUID.fromString(String.valueOf(payload.get("merchantId")));
		List<UUID> endpoints = jdbc.query("""
				SELECT endpoint_id FROM merchant_webhook_endpoint
				WHERE merchant_id=? AND event_type=? AND status='ACTIVE'
				""", (rs, rowNum) -> rs.getObject("endpoint_id", UUID.class), merchantId, event.eventType());

		for (UUID endpointId : endpoints) {
			jdbc.update("""
					INSERT INTO webhook_delivery
					    (delivery_id,event_id,endpoint_id,merchant_id,payment_id,event_type,
					     status,attempt_count,next_attempt_at,version,created_at,updated_at)
					VALUES (?,?,?,?,?,?,'PENDING',0,now(),0,now(),now())
					ON CONFLICT(event_id,endpoint_id) DO NOTHING
					""", UUID.randomUUID(), event.eventId(), endpointId, merchantId, event.aggregateId(),
					event.eventType());
		}
		jdbc.update("UPDATE inbox_event SET processed_at=now() WHERE consumer_name=? AND event_id=?", CONSUMER_NAME,
				event.eventId());
		return true;
	}
}
