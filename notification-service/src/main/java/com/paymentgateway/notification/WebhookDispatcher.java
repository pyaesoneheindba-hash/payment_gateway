package com.paymentgateway.notification;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import tools.jackson.databind.ObjectMapper;

/** Delivers webhook_delivery rows created by the RocketMQ consumer. */
@Component
public class WebhookDispatcher {
	private final JdbcTemplate jdbc;
	private final ObjectMapper mapper;
	private final WebhookNotificationProperties properties;
	private final WebhookSigner signer;
	private final WebhookUrlGuard urlGuard;
	private final HttpClient http;
	private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

	/**
	 * Creates a new {@code WebhookDispatcher} instance.
	 *
	 * @param jdbc       the jdbc value
	 * @param mapper     the object mapper used to serialize and deserialize JSON
	 * @param properties the properties value
	 * @param signer     the signer value
	 * @param urlGuard   the URL guard value
	 */
	public WebhookDispatcher(JdbcTemplate jdbc, ObjectMapper mapper, WebhookNotificationProperties properties,
			WebhookSigner signer, WebhookUrlGuard urlGuard) {
		this.jdbc = jdbc;
		this.mapper = mapper;
		this.properties = properties;
		this.signer = signer;
		this.urlGuard = urlGuard;
		this.http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofMillis(Math.max(500, properties.getConnectTimeoutMs())))
				.followRedirects(HttpClient.Redirect.NEVER).build();
	}

	/**
	 * Executes the deliver due webhooks operation.
	 */
	@Scheduled(fixedDelayString = "${payment.webhook.delivery.delivery-poll-ms:1000}")
	public void deliverDueWebhooks() {
		if (!properties.isEnabled())
			return;
		recoverStaleDeliveryClaims();
		for (UUID deliveryId : claimDueDeliveries()) {
			workers.submit(() -> sendOne(deliveryId));
		}
	}

	/**
	 * Executes the claim due deliveries operation.
	 *
	 * @return the matching values
	 */
	protected List<UUID> claimDueDeliveries() {
		return jdbc.query("""
				WITH picked AS (
				    SELECT delivery_id
				    FROM webhook_delivery
				    WHERE status IN ('PENDING','RETRY') AND next_attempt_at <= now()
				    ORDER BY next_attempt_at, created_at
				    LIMIT ?
				    FOR UPDATE SKIP LOCKED
				)
				UPDATE webhook_delivery d
				SET status='SENDING', attempt_count=d.attempt_count+1, last_attempt_at=now(),
				    version=d.version+1, updated_at=now()
				FROM picked
				WHERE d.delivery_id=picked.delivery_id
				RETURNING d.delivery_id
				""", (rs, rowNum) -> rs.getObject("delivery_id", UUID.class),
				Math.max(1, properties.getDeliveryBatchSize()));
	}

	/**
	 * Sends one.
	 *
	 * @param deliveryId the delivery identifier identifier
	 */
	private void sendOne(UUID deliveryId) {
		DeliveryWork work = loadDelivery(deliveryId);
		if (work == null)
			return;
		if (!"ACTIVE".equals(work.endpointStatus())) {
			markDead(work, null, "Webhook endpoint is not ACTIVE");
			return;
		}
		try {
			URI uri = urlGuard.validate(work.url());
			Map<String, Object> paymentPayload = mapper.readValue(work.payloadJson(), Map.class);
			Map<String, Object> envelope = new LinkedHashMap<>();
			envelope.put("eventId", work.eventId().toString());
			envelope.put("eventType", work.eventType());
			envelope.put("eventVersion", work.eventVersion());
			envelope.put("occurredAt", work.occurredAt().toString());
			envelope.put("payment", paymentPayload);
			byte[] body = mapper.writeValueAsBytes(envelope);
			WebhookSigner.SignedWebhookHeaders signed = signer.sign(work.eventId(), body);

			HttpRequest request = HttpRequest.newBuilder(uri)
					.timeout(Duration.ofMillis(Math.max(500, properties.getRequestTimeoutMs())))
					.header("Content-Type", "application/json").header("Accept", "application/json")
					.header("User-Agent", "payment-gateway-webhook/1.0")
					.header("X-Payment-Event-Id", work.eventId().toString()).header("X-Payment-Key-Id", signed.keyId())
					.header("X-Payment-Timestamp", signed.timestamp()).header("Content-Digest", signed.contentDigest())
					.header("X-Payment-Signature", signed.signature())
					.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();

			HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
			int status = response.statusCode();
			if (status >= 200 && status < 300)
				markDelivered(work, status);
			else
				scheduleRetryOrDead(work, status, "Merchant webhook returned HTTP " + status);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			scheduleRetryOrDead(work, null, "Webhook delivery interrupted");
		} catch (Exception e) {
			scheduleRetryOrDead(work, null, safeError(e));
		}
	}

	/**
	 * Loads delivery.
	 *
	 * @param deliveryId the delivery identifier identifier
	 * @return the resulting {@code DeliveryWork} value
	 */
	private DeliveryWork loadDelivery(UUID deliveryId) {
		List<DeliveryWork> found = jdbc.query("""
				SELECT d.delivery_id, d.event_id, d.payment_id, d.event_type, d.attempt_count,
				       e.event_version, e.payload::text AS payload, e.created_at AS occurred_at,
				       m.url, m.status AS endpoint_status
				FROM webhook_delivery d
				JOIN outbox_event e ON e.event_id=d.event_id
				JOIN merchant_webhook_endpoint m ON m.endpoint_id=d.endpoint_id
				WHERE d.delivery_id=? AND d.status='SENDING'
				""",
				(rs, rowNum) -> new DeliveryWork(rs.getObject("delivery_id", UUID.class),
						rs.getObject("event_id", UUID.class), rs.getObject("payment_id", UUID.class),
						rs.getString("event_type"), rs.getInt("attempt_count"), rs.getInt("event_version"),
						rs.getString("payload"), toInstant(rs.getObject("occurred_at")), rs.getString("url"),
						rs.getString("endpoint_status")),
				deliveryId);
		return found.stream().findFirst().orElse(null);
	}

	/**
	 * Executes the mark delivered operation.
	 *
	 * @param work       the work value
	 * @param httpStatus the HTTP status value
	 */
	private void markDelivered(DeliveryWork work, int httpStatus) {
		jdbc.update("""
				UPDATE webhook_delivery
				SET status='DELIVERED', last_http_status=?, last_error=NULL, delivered_at=now(),
				    version=version+1, updated_at=now()
				WHERE delivery_id=? AND status='SENDING'
				""", httpStatus, work.deliveryId());
	}

	/**
	 * Executes the schedule retry or dead operation.
	 *
	 * @param work       the work value
	 * @param httpStatus the HTTP status value
	 * @param error      the failure that triggered error handling
	 */
	private void scheduleRetryOrDead(DeliveryWork work, Integer httpStatus, String error) {
		if (work.attemptCount() >= Math.max(1, properties.getMaxAttempts())) {
			markDead(work, httpStatus, error);
			return;
		}
		long delaySeconds = properties.retryDelayForAttempt(work.attemptCount());
		jdbc.update("""
				UPDATE webhook_delivery
				SET status='RETRY', next_attempt_at=now() + (? * interval '1 second'),
				    last_http_status=?, last_error=?, version=version+1, updated_at=now()
				WHERE delivery_id=? AND status='SENDING'
				""", delaySeconds, httpStatus, truncate(error, 4000), work.deliveryId());
	}

	/**
	 * Executes the mark dead operation.
	 *
	 * @param work       the work value
	 * @param httpStatus the HTTP status value
	 * @param error      the failure that triggered error handling
	 */
	private void markDead(DeliveryWork work, Integer httpStatus, String error) {
		jdbc.update("""
				UPDATE webhook_delivery
				SET status='DEAD', last_http_status=?, last_error=?, version=version+1, updated_at=now()
				WHERE delivery_id=? AND status='SENDING'
				""", httpStatus, truncate(error, 4000), work.deliveryId());
	}

	/**
	 * Executes the recover stale delivery claims operation.
	 */
	private void recoverStaleDeliveryClaims() {
		long seconds = Math.max(30, properties.getStaleClaimSeconds());
		jdbc.update("""
				UPDATE webhook_delivery
				SET status='RETRY', next_attempt_at=now(), last_error='Recovered stale SENDING claim',
				    version=version+1, updated_at=now()
				WHERE status='SENDING' AND updated_at < now() - (? * interval '1 second')
				""", seconds);
	}

	/**
	 * Converts the current value to instant.
	 *
	 * @param value the value to process
	 * @return the resulting {@code Instant} value
	 */
	private static Instant toInstant(Object value) {
		if (value instanceof Instant i)
			return i;
		if (value instanceof OffsetDateTime o)
			return o.toInstant();
		if (value instanceof Timestamp t)
			return t.toInstant();

		throw new IllegalArgumentException(
				"Unsupported timestamp type: " + (value == null ? "null" : value.getClass().getName()));
	}

	/**
	 * Executes the safe error operation.
	 *
	 * @param e the exception that caused the operation to fail
	 * @return the resulting safe error text
	 */
	private static String safeError(Exception e) {
		String msg = e.getMessage();
		return e.getClass().getSimpleName() + (msg == null || msg.isBlank() ? "" : ": " + msg);
	}

	/**
	 * Executes the truncate operation.
	 *
	 * @param value the value to process
	 * @param max   the max value
	 * @return the resulting truncate text
	 */
	private static String truncate(String value, int max) {
		if (value == null || value.length() <= max)
			return value;
		return value.substring(0, max);
	}

	/**
	 * Executes the shutdown operation.
	 */
	@PreDestroy
	public void shutdown() {
		workers.shutdownNow();
	}

	/**
	 * Immutable data carrier for delivery work.
	 */
	private record DeliveryWork(UUID deliveryId, UUID eventId, UUID paymentId, String eventType, int attemptCount,
			int eventVersion, String payloadJson, Instant occurredAt, String url, String endpointStatus) {
	}
}
