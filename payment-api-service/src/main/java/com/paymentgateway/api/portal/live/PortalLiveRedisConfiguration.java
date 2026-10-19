package com.paymentgateway.api.portal.live;

import com.paymentgateway.common.live.PortalLiveEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * Configures portal live Redis application components.
 */
@Configuration
@ConditionalOnProperty(prefix = "payment.portal-live", name = "enabled", havingValue = "true")
public class PortalLiveRedisConfiguration {
    private static final Logger log = LoggerFactory.getLogger(PortalLiveRedisConfiguration.class);

    /**
     * Executes the portal live Redis listener operation.
     *
     * @param connectionFactory the connection factory value
     * @param hub the hub value
     * @param mapper the object mapper used to serialize and deserialize JSON
     * @param channel the channel value
     * @return the resulting {@code RedisMessageListenerContainer} value
     */
    @Bean
    RedisMessageListenerContainer portalLiveRedisListener(
            RedisConnectionFactory connectionFactory,
            PortalLiveEventHub hub,
            ObjectMapper mapper,
            @Value("${payment.portal-live.redis-channel:txnhub.portal.payment-events}") String channel) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> {
            try {
                PortalLiveEvent event = mapper.readValue(message.getBody(), PortalLiveEvent.class);
                hub.publish(event);
            }
            catch (Exception e) {
                log.warn("Ignoring malformed portal live event from Redis", e);
            }
        }, new ChannelTopic(channel));
        return container;
    }
}
