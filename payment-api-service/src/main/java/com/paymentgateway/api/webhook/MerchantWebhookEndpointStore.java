package com.paymentgateway.api.webhook;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Implements merchant webhook endpoint store behavior for the payment gateway
 * platform.
 */
@Repository
public class MerchantWebhookEndpointStore {
	private final JdbcTemplate jdbc;

	/**
	 * Creates a new {@code MerchantWebhookEndpointStore} instance.
	 *
	 * @param jdbc the jdbc value
	 */
	public MerchantWebhookEndpointStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Executes the create operation.
	 *
	 * @param merchantId  the merchant identifier
	 * @param url         the URL value
	 * @param description the description value
	 * @return the resulting {@code MerchantWebhookEndpointRecord} value
	 */
	public MerchantWebhookEndpointRecord create(UUID merchantId, String url, String description) {
		UUID id = UUID.randomUUID();
		try {
			jdbc.update(
					"""
							INSERT INTO merchant_webhook_endpoint
							    (endpoint_id, merchant_id, event_type, url, description, status, version, created_at, updated_at)
							VALUES (?, ?, 'PAYMENT_STATUS_CHANGED', ?, ?, 'ACTIVE', 0, now(), now())
							""",
					id, merchantId, url, description);
		} catch (DataIntegrityViolationException e) {
			throw new IllegalArgumentException("Webhook endpoint is already registered for this merchant", e);
		}
		return findOwned(id, merchantId).orElseThrow();
	}

	/**
	 * Executes the list operation.
	 *
	 * @param merchantId the merchant identifier
	 * @return the matching values
	 */
	public List<MerchantWebhookEndpointRecord> list(UUID merchantId) {
		return jdbc.query("""
				SELECT endpoint_id, merchant_id, event_type, url, description, status, created_at, updated_at
				FROM merchant_webhook_endpoint
				WHERE merchant_id = ?
				ORDER BY created_at DESC
				""", this::map, merchantId);
	}

	/**
	 * Finds owned.
	 *
	 * @param endpointId the webhook endpoint identifier
	 * @param merchantId the merchant identifier
	 * @return the matching value when present
	 */
	public Optional<MerchantWebhookEndpointRecord> findOwned(UUID endpointId, UUID merchantId) {
		List<MerchantWebhookEndpointRecord> found = jdbc.query("""
				SELECT endpoint_id, merchant_id, event_type, url, description, status, created_at, updated_at
				FROM merchant_webhook_endpoint
				WHERE endpoint_id = ? AND merchant_id = ?
				""", this::map, endpointId, merchantId);
		return found.stream().findFirst();
	}

	/**
	 * Executes the change status operation.
	 *
	 * @param endpointId the webhook endpoint identifier
	 * @param merchantId the merchant identifier
	 * @param status     the payment or delivery status
	 * @return the resulting {@code MerchantWebhookEndpointRecord} value
	 */
	public MerchantWebhookEndpointRecord changeStatus(UUID endpointId, UUID merchantId, String status) {
		int updated = jdbc.update("""
				UPDATE merchant_webhook_endpoint
				SET status = ?, version = version + 1, updated_at = now()
				WHERE endpoint_id = ? AND merchant_id = ?
				""", status, endpointId, merchantId);
		if (updated != 1)
			throw new IllegalArgumentException("Webhook endpoint not found");
		return findOwned(endpointId, merchantId).orElseThrow();
	}

	/**
	 * Executes the map operation.
	 *
	 * @param rs     the rs value
	 * @param rowNum the row num value
	 * @return the resulting {@code MerchantWebhookEndpointRecord} value
	 * @throws SQLException if SQL processing fails
	 */
	private MerchantWebhookEndpointRecord map(ResultSet rs, int rowNum) throws SQLException {
		return new MerchantWebhookEndpointRecord(rs.getObject("endpoint_id", UUID.class),
				rs.getObject("merchant_id", UUID.class), rs.getString("event_type"), rs.getString("url"),
				rs.getString("description"), rs.getString("status"), toInstant(rs.getObject("created_at")),
				toInstant(rs.getObject("updated_at")));
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
}
