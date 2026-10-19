package com.paymentgateway.gateway.security.portal;

import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * Local authorization/merchant binding for externally authenticated OIDC users.
 */
@Repository
public class PortalUserRepository {
	private final DatabaseClient db;

	/**
	 * Creates a new {@code PortalUserRepository} instance.
	 *
	 * @param db the db value
	 */
	public PortalUserRepository(DatabaseClient db) {
		this.db = db;
	}

	/**
	 * Finds by OIDC subject.
	 *
	 * @param subject the subject value
	 * @return a reactive result that completes with the requested value
	 */
	public Mono<PortalUserRecord> findByOidcSubject(String subject) {
		return db.sql("""
				SELECT p.portal_user_id, p.username, p.display_name, p.role, p.merchant_id, p.status,
				       m.status AS merchant_status
				FROM portal_user p
				LEFT JOIN merchant m ON m.merchant_id=p.merchant_id
				WHERE p.oidc_subject=:subject
				""").bind("subject", subject)
				.map((row, meta) -> new PortalUserRecord(row.get("portal_user_id", UUID.class),
						row.get("username", String.class), row.get("display_name", String.class),
						row.get("role", String.class), row.get("merchant_id", UUID.class),
						row.get("status", String.class), row.get("merchant_status", String.class)))
				.one();
	}
}
