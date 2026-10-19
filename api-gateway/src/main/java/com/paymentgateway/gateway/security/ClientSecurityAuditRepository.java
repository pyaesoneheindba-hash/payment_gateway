package com.paymentgateway.gateway.security;

import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * Provides persistence access for client security audit data.
 */
@Repository
public class ClientSecurityAuditRepository {
	private static final String INSERT = """
			INSERT INTO client_request_security_log(
			    security_event_id, correlation_id, merchant_id, key_id,
			    http_method, request_path, request_timestamp, nonce_hash,
			    body_hash, idempotency_key_hash, signature_algorithm,
			    signature_verified, replay_detected, decision, failure_reason
			) VALUES (
			    :securityEventId, :correlationId, :merchantId, :keyId,
			    :httpMethod, :requestPath,
			    CASE WHEN :requestTimestampEpoch IS NULL THEN NULL ELSE to_timestamp(:requestTimestampEpoch) END,
			    :nonceHash, :bodyHash, :idempotencyKeyHash, :signatureAlgorithm,
			    :signatureVerified, :replayDetected, :decision, :failureReason
			)
			""";

	private final DatabaseClient databaseClient;

	/**
	 * Creates a new {@code ClientSecurityAuditRepository} instance.
	 *
	 * @param databaseClient the database client value
	 */
	public ClientSecurityAuditRepository(DatabaseClient databaseClient) {
		this.databaseClient = databaseClient;
	}

	/**
	 * Executes the record operation.
	 *
	 * @param event the event to process
	 * @return a reactive result that completes with the requested value
	 */
	public Mono<Void> record(ClientSecurityAuditEvent event) {
		DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(INSERT)
				.bind("securityEventId", event.securityEventId()).bind("httpMethod", event.httpMethod())
				.bind("requestPath", event.requestPath()).bind("decision", event.decision());

		spec = bind(spec, "correlationId", event.correlationId(), String.class);
		spec = bind(spec, "merchantId", event.merchantId(), UUID.class);
		spec = bind(spec, "keyId", event.keyId(), String.class);
		spec = bind(spec, "requestTimestampEpoch", event.requestTimestampEpoch(), Long.class);
		spec = bind(spec, "nonceHash", event.nonceHash(), String.class);
		spec = bind(spec, "bodyHash", event.bodyHash(), String.class);
		spec = bind(spec, "idempotencyKeyHash", event.idempotencyKeyHash(), String.class);
		spec = bind(spec, "signatureAlgorithm", event.signatureAlgorithm(), String.class);
		spec = bind(spec, "signatureVerified", event.signatureVerified(), Boolean.class);
		spec = bind(spec, "replayDetected", event.replayDetected(), Boolean.class);
		spec = bind(spec, "failureReason", event.failureReason(), String.class);
		return spec.fetch().rowsUpdated().then();
	}

	/**
	 * Executes the bind operation.
	 *
	 * @param spec  the spec value
	 * @param name  the name value
	 * @param value the value to process
	 * @param type  the type value
	 * @return the resulting {@code DatabaseClient.GenericExecuteSpec} value
	 */
	private static <T> DatabaseClient.GenericExecuteSpec bind(DatabaseClient.GenericExecuteSpec spec, String name,
			T value, Class<T> type) {
		return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
	}
}
