package com.paymentgateway.gateway.security;

import java.time.Instant;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * Provides persistence access for merchant signing key data.
 */
@Repository
public class MerchantSigningKeyRepository {
	private final DatabaseClient databaseClient;

	/**
	 * Creates a new {@code MerchantSigningKeyRepository} instance.
	 *
	 * @param databaseClient the database client value
	 */
	public MerchantSigningKeyRepository(DatabaseClient databaseClient) {
		this.databaseClient = databaseClient;
	}

	/**
	 * Executes the find operation.
	 *
	 * @param merchantId the merchant identifier
	 * @param keyId      the signing key identifier
	 * @return a reactive result that completes with the requested value
	 */
	public Mono<MerchantSigningKey> find(UUID merchantId, String keyId) {
		return databaseClient.sql("""
				SELECT k.key_id, k.merchant_id, k.algorithm, k.public_key, k.status,
				       m.status AS merchant_status,
				       EXTRACT(EPOCH FROM k.valid_from)::bigint AS valid_from_epoch,
				       EXTRACT(EPOCH FROM k.valid_until)::bigint AS valid_until_epoch
				  FROM merchant_api_key k
				  JOIN merchant m ON m.merchant_id = k.merchant_id
				 WHERE k.merchant_id = :merchantId
				   AND k.key_id = :keyId
				""").bind("merchantId", merchantId).bind("keyId", keyId).map((row, metadata) -> {
			Long from = row.get("valid_from_epoch", Long.class);
			Long until = row.get("valid_until_epoch", Long.class);
			return new MerchantSigningKey(row.get("key_id", String.class), row.get("merchant_id", UUID.class),
					row.get("algorithm", String.class), row.get("public_key", String.class),
					row.get("status", String.class), row.get("merchant_status", String.class),
					from == null ? Instant.EPOCH : Instant.ofEpochSecond(from),
					until == null ? null : Instant.ofEpochSecond(until));
		}).one();
	}
}
