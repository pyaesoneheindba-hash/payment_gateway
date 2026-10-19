package com.paymentgateway.core.persistence.entity;

import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.paymentgateway.common.MessagingNames;
import com.paymentgateway.persistence.BaseAuditableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Represents the persisted outbox event state.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEventEntity extends BaseAuditableEntity {
	@Id
	@Column(name = "event_id")
	private UUID eventId;
	@Column(name = "aggregate_type", nullable = false, length = 64)
	private String aggregateType;
	@Column(name = "aggregate_id", nullable = false)
	private UUID aggregateId;
	@Column(name = "event_type", nullable = false, length = 128)
	private String eventType;
	@Column(name = "event_version", nullable = false)
	private int eventVersion;
	@Column(nullable = false, length = 160)
	private String topic;
	@Column(name = "message_key", nullable = false, length = 160)
	private String messageKey;
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private Map<String, Object> payload;
	@Column(nullable = false, length = 16)
	private String status;

	/**
	 * Creates a new {@code OutboxEventEntity} instance.
	 */
	protected OutboxEventEntity() {
	}

	/**
	 * Executes the pending operation.
	 *
	 * @param aggregateId the aggregate identifier identifier
	 * @param type        the type value
	 * @param payload     the payload to process
	 * @return the resulting {@code OutboxEventEntity} value
	 */
	public static OutboxEventEntity pending(UUID aggregateId, String type, Map<String, Object> payload) {
		var e = new OutboxEventEntity();
		e.eventId = UUID.randomUUID();
		e.aggregateType = "PAYMENT";
		e.aggregateId = aggregateId;
		e.eventType = type;
		e.eventVersion = 1;
		e.topic = MessagingNames.PAYMENT_LIFECYCLE_TOPIC;
		e.messageKey = aggregateId.toString();
		e.payload = payload;
		e.status = "NEW";
		return e;
	}
}
