package com.paymentgateway.gateway.security.publictoken;

import java.time.Instant;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * Provides persistence access for public token security audit data.
 */
@Repository
public class PublicTokenSecurityAuditRepository {
	private static final String INSERT = """
			INSERT INTO public_token_security_log(
			  security_event_id, correlation_id, merchant_id, token_subject,
			  token_jti_hash, token_scopes, http_method, request_path,
			  token_expires_at, one_time, replay_detected, decision, failure_reason
			) VALUES (
			  :securityEventId, :correlationId, :merchantId, :tokenSubject,
			  :tokenJtiHash, :tokenScopes, :httpMethod, :requestPath,
			  :tokenExpiresAt, :oneTime, :replayDetected, :decision, :failureReason
			)
			""";

	private final DatabaseClient databaseClient;

	/**
	 * Creates a new {@code PublicTokenSecurityAuditRepository} instance.
	 *
	 * @param databaseClient the database client value
	 */
	public PublicTokenSecurityAuditRepository(DatabaseClient databaseClient) {
		this.databaseClient = databaseClient;
	}

	/**
	 * Executes the record operation.
	 *
	 * @param event the event to process
	 * @return a reactive result that completes with the requested value
	 */
	public Mono<Void> record(PublicTokenSecurityAuditEvent event) {
		DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(INSERT)
				.bind("securityEventId", event.securityEventId()).bind("httpMethod", event.httpMethod())
				.bind("requestPath", event.requestPath()).bind("decision", event.decision());
		spec = bind(spec, "correlationId", event.correlationId(), String.class);
		spec = bind(spec, "merchantId", event.merchantId(), UUID.class);
		spec = bind(spec, "tokenSubject", event.subject(), String.class);
		spec = bind(spec, "tokenJtiHash", event.jtiHash(), String.class);
		spec = bind(spec, "tokenScopes", event.scopes(), String.class);
		spec = bind(spec, "tokenExpiresAt", event.tokenExpiresAt(), Instant.class);
		spec = bind(spec, "oneTime", event.oneTime(), Boolean.class);
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
