package com.paymentgateway.common;

/** Stable messaging resource names shared by producers and consumers. */
public final class MessagingNames {
	public static final String PAYMENT_LIFECYCLE_TOPIC = "payment_lifecycle_v1";
	public static final String PAYMENT_STATUS_CHANGED_TAG = "PAYMENT_STATUS_CHANGED";
	public static final String NOTIFICATION_CONSUMER_GROUP = "notification-payment-status-v1";

	/**
	 * Creates a new {@code MessagingNames} instance.
	 */
	private MessagingNames() {
	}
}
