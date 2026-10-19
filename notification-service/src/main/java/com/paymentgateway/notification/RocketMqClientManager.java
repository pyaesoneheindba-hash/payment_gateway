package com.paymentgateway.notification;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.ByteBuffer;
import java.util.Collections;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.SessionCredentialsProvider;
import org.apache.rocketmq.client.apis.StaticSessionCredentialsProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Apache RocketMQ 5.x producer + push-consumer lifecycle. Connect to RocketMQ Proxy endpoints. */
@Component
@ConditionalOnProperty(prefix = "payment.rocketmq", name = "enabled", havingValue = "true")
public class RocketMqClientManager {
    private static final Logger log = LoggerFactory.getLogger(RocketMqClientManager.class);
    private final RocketMqProperties properties;
    private final PaymentStatusEventHandler handler;
    private final ObjectMapper mapper;
    private ClientServiceProvider provider;
    private Producer producer;
    private PushConsumer consumer;

    /**
     * Creates a new {@code RocketMqClientManager} instance.
     *
     * @param properties the properties value
     * @param handler the handler value
     * @param mapper the object mapper used to serialize and deserialize JSON
     */
    public RocketMqClientManager(RocketMqProperties properties, PaymentStatusEventHandler handler, ObjectMapper mapper) {
        this.properties = properties;
        this.handler = handler;
        this.mapper = mapper;
    }

    /**
     * Executes the start operation.
     *
     * @throws Exception if  processing fails
     */
    @PostConstruct
    void start() throws Exception {
        provider = ClientServiceProvider.loadService();
        var builder = ClientConfiguration.newBuilder()
                .setEndpoints(properties.getEndpoints())
                .enableSsl(properties.isSslEnabled());
        if (!properties.getAccessKey().isBlank() || !properties.getSecretKey().isBlank()) {
            if (properties.getAccessKey().isBlank() || properties.getSecretKey().isBlank()) {
                throw new IllegalStateException("ROCKETMQ_ACCESS_KEY and ROCKETMQ_SECRET_KEY must be configured together");
            }
            SessionCredentialsProvider credentials = new StaticSessionCredentialsProvider(
                    properties.getAccessKey(), properties.getSecretKey());
            builder.setCredentialProvider(credentials);
        }
        ClientConfiguration configuration = builder.build();
        producer = provider.newProducerBuilder()
                .setClientConfiguration(configuration)
                .setTopics(properties.getTopic())
                .build();

        FilterExpression filter = new FilterExpression(properties.getPaymentStatusTag(), FilterExpressionType.TAG);
        consumer = provider.newPushConsumerBuilder()
                .setClientConfiguration(configuration)
                .setConsumerGroup(properties.getConsumerGroup())
                .setSubscriptionExpressions(Collections.singletonMap(properties.getTopic(), filter))
                .setMessageListener(message -> {
                    try {
                        ByteBuffer body = message.getBody().duplicate();
                        byte[] bytes = new byte[body.remaining()];
                        body.get(bytes);
                        PaymentLifecycleMessage event = mapper.readValue(bytes, PaymentLifecycleMessage.class);
                        if ("PAYMENT_CREATED".equals(event.eventType())) {
                            // PAYMENT_CREATED is portal-live only. It already reached Redis/SSE directly
                            // from the committed outbox relay and must not create merchant webhooks.
                            return ConsumeResult.SUCCESS;
                        }
                        handler.handle(event, message.getMessageId().toString());
                        return ConsumeResult.SUCCESS;
                    }
                    catch (Exception e) {
                        log.error("RocketMQ payment-status consumption failed; message will be retried, messageId={}",
                                message.getMessageId(), e);
                        return ConsumeResult.FAILURE;
                    }
                })
                .build();
        log.info("RocketMQ enabled: endpoints={}, topic={}, consumerGroup={}",
                properties.getEndpoints(), properties.getTopic(), properties.getConsumerGroup());
    }

    /**
     * Executes the send operation.
     *
     * @param topic the topic value
     * @param tag the tag value
     * @param key the lookup or signing key
     * @param body the request or response body
     * @throws Exception if  processing fails
     */
    public void send(String topic, String tag, String key, byte[] body) throws Exception {
        Message message = provider.newMessageBuilder()
                .setTopic(topic)
                .setTag(tag)
                .setKeys(key)
                .setBody(body)
                .build();
        producer.send(message);
    }

    /**
     * Executes the close operation.
     */
    @PreDestroy
    void close() {
        try { if (consumer != null) consumer.close(); }
        catch (Exception e) { log.warn("Could not close RocketMQ consumer cleanly", e); }
        try { if (producer != null) producer.close(); }
        catch (Exception e) { log.warn("Could not close RocketMQ producer cleanly", e); }
    }
}
