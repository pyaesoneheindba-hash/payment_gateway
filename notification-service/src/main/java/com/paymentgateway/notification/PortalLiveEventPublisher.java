package com.paymentgateway.notification;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.paymentgateway.common.live.PortalLiveEvent;

import tools.jackson.databind.ObjectMapper;

/**
 * Publishes committed outbox lifecycle events to Redis Pub/Sub for live portal
 * SSE delivery. This is a best-effort UI notification path; portal clients
 * reload authoritative REST data.
 */
@Component
@ConditionalOnProperty(prefix = "payment.portal-live", name = "enabled", havingValue = "true")
public class PortalLiveEventPublisher {
	private static final Logger log = LoggerFactory.getLogger(PortalLiveEventPublisher.class);

	private final StringRedisTemplate redis;
	private final ObjectMapper mapper;
	private final String channel;

	/**
	 * Creates a new {@code PortalLiveEventPublisher} instance.
	 *
	 * @param redis   the Redis value
	 * @param mapper  the object mapper used to serialize and deserialize JSON
	 * @param channel the channel value
	 */
	public PortalLiveEventPublisher(StringRedisTemplate redis, ObjectMapper mapper,
			@Value("${payment.portal-live.redis-channel:txnhub.portal.payment-events}") String channel) {
		this.redis = redis;
		this.mapper = mapper;
		this.channel = channel;
	}

	/**
	 * Executes the publish operation.
	 *
	 * @param message the message value
	 */
	public void publish(PaymentLifecycleMessage message) {
		try {
			Map<String, Object> payload = message.payload() == null ? Map.of() : message.payload();
			Object merchantValue = payload.get("merchantId");
			if (merchantValue == null) {
				log.warn("Skipping portal live event {} because merchantId is missing", message.eventId());
				return;
			}
			UUID merchantId = UUID.fromString(String.valueOf(merchantValue));
			Map<String, Object> data = new LinkedHashMap<>(payload);
			// merchantId/paymentId are first-class envelope fields and do not need to be
			// duplicated.
			data.remove("merchantId");
			data.remove("paymentId");
			PortalLiveEvent event = new PortalLiveEvent(message.eventId(), message.eventType(), merchantId,
					message.aggregateId(), message.occurredAt(), data);
			redis.convertAndSend(channel, mapper.writeValueAsString(event));
		} catch (Exception e) {
			// Live portal delivery must never fail or delay the durable RocketMQ/webhook
			// path.
			log.warn("Could not publish portal live event {}; dashboard will recover via periodic sync",
					message == null ? null : message.eventId(), e);
		}
	}
}
