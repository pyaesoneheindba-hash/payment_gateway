package com.paymentgateway.gateway.portal.live;

import com.paymentgateway.common.live.PortalLiveEvent;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;
import tools.jackson.databind.ObjectMapper;

/**
 * Resilient gateway-owned Redis subscription for portal live events.
 *
 * <p>
 * A dedicated {@link ReactiveRedisMessageListenerContainer} owns the Pub/Sub
 * connection for the lifetime of the gateway bean. Browser SSE
 * connect/disconnect cycles therefore unsubscribe logical listeners without
 * destroying a template-bound Redis connection that later subscribers might try
 * to reuse.
 * </p>
 *
 * <p>
 * Each caller gets a lazy subscription. If Lettuce reports a transient closed
 * connection, the subscription is recreated with bounded exponential backoff.
 * The retry is intentionally unbounded because portal SSE is a notification
 * channel; authoritative state remains in PostgreSQL and the portal keeps its
 * periodic safety refresh.
 * </p>
 */
@Component
public class PortalLiveRedisStream implements DisposableBean {
	private static final Logger log = LoggerFactory.getLogger(PortalLiveRedisStream.class);

	private final ReactiveRedisMessageListenerContainer container;
	private final ObjectMapper mapper;
	private final ChannelTopic topic;
	private final Duration retryMin;
	private final Duration retryMax;

	/**
	 * Creates a new {@code PortalLiveRedisStream} instance.
	 *
	 * @param connectionFactory the connection factory value
	 * @param mapper            the object mapper used to serialize and deserialize
	 *                          JSON
	 * @param channel           the channel value
	 * @param retryMinMs        the retry min ms value
	 * @param retryMaxMs        the retry max ms value
	 */
	public PortalLiveRedisStream(ReactiveRedisConnectionFactory connectionFactory, ObjectMapper mapper,
			@Value("${payment.portal-live.redis-channel:txnhub.portal.payment-events}") String channel,
			@Value("${payment.portal-live.redis-retry-min-ms:1000}") long retryMinMs,
			@Value("${payment.portal-live.redis-retry-max-ms:10000}") long retryMaxMs) {
		this.container = new ReactiveRedisMessageListenerContainer(connectionFactory);
		this.mapper = mapper;
		this.topic = ChannelTopic.of(channel);
		this.retryMin = Duration.ofMillis(Math.max(250, retryMinMs));
		this.retryMax = Duration.ofMillis(Math.max(this.retryMin.toMillis(), retryMaxMs));
	}

	/**
	 * Streams live portal payment events and heartbeat messages to the
	 * authenticated client.
	 *
	 * @return a reactive stream of the requested values
	 */
	public Flux<PortalLiveEvent> events() {
		return Flux.defer(() -> {
			log.debug("Opening portal-live Redis subscription on {}", topic.getTopic());
			return container.receive(topic).map(message -> decode(mapper, message.getMessage()))
					.filter(Objects::nonNull);
		}).retryWhen(Retry.backoff(Long.MAX_VALUE, retryMin).maxBackoff(retryMax).jitter(0.20d)
				.doBeforeRetry(signal -> log.warn("Portal-live Redis subscription lost; reconnecting (attempt {}): {}",
						signal.totalRetries() + 1,
						signal.failure() == null ? "unknown error" : signal.failure().toString())))
				.doOnCancel(() -> log.debug("Portal-live Redis subscriber cancelled"));
	}

	/**
	 * Executes the destroy operation.
	 */
	@Override
	public void destroy() {
		container.destroy();
	}

	/**
	 * Executes the decode operation.
	 *
	 * @param mapper the object mapper used to serialize and deserialize JSON
	 * @param json   the JSON value
	 * @return the resulting {@code PortalLiveEvent} value
	 */
	private static PortalLiveEvent decode(ObjectMapper mapper, String json) {
		try {
			return mapper.readValue(json, PortalLiveEvent.class);
		} catch (Exception e) {
			log.warn("Ignoring malformed portal live event from Redis", e);
			return null;
		}
	}
}
