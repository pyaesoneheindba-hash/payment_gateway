package com.paymentgateway.notification;

import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Prevents a silent configuration in which webhook delivery is enabled but the
 * outbox -> RocketMQ -> inbox fan-out path is disabled.
 */
@Component
public class MessagingConfigurationGuard {
	private final RocketMqProperties rocketMq;
	private final WebhookNotificationProperties webhook;

	/**
	 * Creates a new {@code MessagingConfigurationGuard} instance.
	 * 
	 * @param rocketMq the rocket mq value
	 * @param webhook  the webhook value
	 */
	public MessagingConfigurationGuard(RocketMqProperties rocketMq, WebhookNotificationProperties webhook) {
		this.rocketMq = rocketMq;
		this.webhook = webhook;
	}

	/**
	 * Executes the validate operation.
	 */
	@PostConstruct
	void validate() {
		if (webhook.isEnabled() && !rocketMq.isEnabled()) {
			throw new IllegalStateException("PAYMENT_WEBHOOK_ENABLED=true requires ROCKETMQ_ENABLED=true; "
					+ "webhook fan-out is driven by the RocketMQ inbox consumer");
		}
		if (rocketMq.isEnabled()) {
			if (rocketMq.getEndpoints() == null || rocketMq.getEndpoints().isBlank()) {
				throw new IllegalStateException("ROCKETMQ_ENDPOINTS is required when RocketMQ is enabled");
			}
			if (rocketMq.getTopic() == null || rocketMq.getTopic().isBlank()) {
				throw new IllegalStateException("ROCKETMQ_PAYMENT_TOPIC is required when RocketMQ is enabled");
			}
			if (rocketMq.getConsumerGroup() == null || rocketMq.getConsumerGroup().isBlank()) {
				throw new IllegalStateException(
						"ROCKETMQ_NOTIFICATION_CONSUMER_GROUP is required when RocketMQ is enabled");
			}
		}
	}
}
