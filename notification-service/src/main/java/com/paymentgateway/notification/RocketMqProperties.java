package com.paymentgateway.notification;

import com.paymentgateway.common.MessagingNames;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds configuration properties for rocket mq.
 */
@Component
@ConfigurationProperties(prefix = "payment.rocketmq")
public class RocketMqProperties {
    private boolean enabled = false;
    private String endpoints = "127.0.0.1:8081";
    private boolean sslEnabled = false;
    private String accessKey = "";
    private String secretKey = "";
    private String topic = MessagingNames.PAYMENT_LIFECYCLE_TOPIC;
    private String paymentStatusTag = MessagingNames.PAYMENT_STATUS_CHANGED_TAG;
    private String consumerGroup = MessagingNames.NOTIFICATION_CONSUMER_GROUP;
    private int outboxBatchSize = 50;
    private int maxPublishAttempts = 20;
    private long publishRetryBaseSeconds = 2;
    private long publishRetryMaxSeconds = 60;
    private long outboxPollMs = 500;
    private long staleClaimSeconds = 60;

    /**
     * Determines whether enabled.
     *
     * @return {@code true} when the condition is satisfied; otherwise {@code false}
     */
    public boolean isEnabled() { return enabled; }
    /**
     * Executes the set enabled operation.
     *
     * @param enabled whether enabled is enabled
     */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    /**
     * Retrieves endpoints.
     *
     * @return the resulting get endpoints text
     */
    public String getEndpoints() { return endpoints; }
    /**
     * Executes the set endpoints operation.
     *
     * @param endpoints the endpoints value
     */
    public void setEndpoints(String endpoints) { this.endpoints = endpoints; }
    /**
     * Determines whether ssl enabled.
     *
     * @return {@code true} when the condition is satisfied; otherwise {@code false}
     */
    public boolean isSslEnabled() { return sslEnabled; }
    /**
     * Executes the set ssl enabled operation.
     *
     * @param sslEnabled whether ssl enabled is enabled
     */
    public void setSslEnabled(boolean sslEnabled) { this.sslEnabled = sslEnabled; }
    /**
     * Retrieves access key.
     *
     * @return the resulting get access key text
     */
    public String getAccessKey() { return accessKey; }
    /**
     * Executes the set access key operation.
     *
     * @param accessKey the access key value
     */
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
    /**
     * Retrieves secret key.
     *
     * @return the resulting get secret key text
     */
    public String getSecretKey() { return secretKey; }
    /**
     * Executes the set secret key operation.
     *
     * @param secretKey the secret key value
     */
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    /**
     * Retrieves topic.
     *
     * @return the resulting get topic text
     */
    public String getTopic() { return topic; }
    /**
     * Executes the set topic operation.
     *
     * @param topic the topic value
     */
    public void setTopic(String topic) { this.topic = topic; }
    /**
     * Retrieves payment status tag.
     *
     * @return the resulting get payment status tag text
     */
    public String getPaymentStatusTag() { return paymentStatusTag; }
    /**
     * Executes the set payment status tag operation.
     *
     * @param paymentStatusTag the payment status tag value
     */
    public void setPaymentStatusTag(String paymentStatusTag) { this.paymentStatusTag = paymentStatusTag; }
    /**
     * Retrieves consumer group.
     *
     * @return the resulting get consumer group text
     */
    public String getConsumerGroup() { return consumerGroup; }
    /**
     * Executes the set consumer group operation.
     *
     * @param consumerGroup the consumer group value
     */
    public void setConsumerGroup(String consumerGroup) { this.consumerGroup = consumerGroup; }
    /**
     * Retrieves outbox batch size.
     *
     * @return the resulting get outbox batch size value
     */
    public int getOutboxBatchSize() { return outboxBatchSize; }
    /**
     * Executes the set outbox batch size operation.
     *
     * @param outboxBatchSize the outbox batch size value
     */
    public void setOutboxBatchSize(int outboxBatchSize) { this.outboxBatchSize = outboxBatchSize; }
    /**
     * Retrieves max publish attempts.
     *
     * @return the resulting get max publish attempts value
     */
    public int getMaxPublishAttempts() { return maxPublishAttempts; }
    /**
     * Executes the set max publish attempts operation.
     *
     * @param maxPublishAttempts the max publish attempts value
     */
    public void setMaxPublishAttempts(int maxPublishAttempts) { this.maxPublishAttempts = maxPublishAttempts; }
    /**
     * Retrieves publish retry base seconds.
     *
     * @return the resulting get publish retry base seconds value
     */
    public long getPublishRetryBaseSeconds() { return publishRetryBaseSeconds; }
    /**
     * Executes the set publish retry base seconds operation.
     *
     * @param publishRetryBaseSeconds the publish retry base seconds value
     */
    public void setPublishRetryBaseSeconds(long publishRetryBaseSeconds) { this.publishRetryBaseSeconds = publishRetryBaseSeconds; }
    /**
     * Retrieves publish retry max seconds.
     *
     * @return the resulting get publish retry max seconds value
     */
    public long getPublishRetryMaxSeconds() { return publishRetryMaxSeconds; }
    /**
     * Executes the set publish retry max seconds operation.
     *
     * @param publishRetryMaxSeconds the publish retry max seconds value
     */
    public void setPublishRetryMaxSeconds(long publishRetryMaxSeconds) { this.publishRetryMaxSeconds = publishRetryMaxSeconds; }
    /**
     * Retrieves outbox poll ms.
     *
     * @return the resulting get outbox poll ms value
     */
    public long getOutboxPollMs() { return outboxPollMs; }
    /**
     * Executes the set outbox poll ms operation.
     *
     * @param outboxPollMs the outbox poll ms value
     */
    public void setOutboxPollMs(long outboxPollMs) { this.outboxPollMs = outboxPollMs; }
    /**
     * Retrieves stale claim seconds.
     *
     * @return the resulting get stale claim seconds value
     */
    public long getStaleClaimSeconds() { return staleClaimSeconds; }
    /**
     * Executes the set stale claim seconds operation.
     *
     * @param staleClaimSeconds the stale claim seconds value
     */
    public void setStaleClaimSeconds(long staleClaimSeconds) { this.staleClaimSeconds = staleClaimSeconds; }
}
