package com.paymentgateway.api.checkout;

import com.paymentgateway.common.Hashing;
import com.paymentgateway.common.PaymentStatus;
import com.paymentgateway.contract.payment.PaymentRpcService;
import com.paymentgateway.contract.payment.PaymentView;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provides checkout session application services.
 */
@Service
public class CheckoutSessionService {
	private final JdbcTemplate jdbc;
	private final SecureRandom random = new SecureRandom();
	private final String publicBaseUrl;
	private final long ttlSeconds;

	@DubboReference(protocol = "tri", version = "1.0.0", group = "payment", check = false, timeout = 10000, retries = 0)
	private PaymentRpcService paymentRpcService;

	/**
	 * Creates a new {@code CheckoutSessionService} instance.
	 *
	 * @param jdbc          the jdbc value
	 * @param publicBaseUrl the public base URL value
	 * @param ttlSeconds    the ttl seconds value
	 */
	public CheckoutSessionService(JdbcTemplate jdbc,
			@Value("${payment.checkout.public-base-url:http://127.0.0.1:4200}") String publicBaseUrl,
			@Value("${payment.checkout.ttl-seconds:300}") long ttlSeconds) {
		this.jdbc = jdbc;
		this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
		this.ttlSeconds = Math.max(60, Math.min(ttlSeconds, 900));
	}

	/**
	 * Immutable data carrier for created.
	 */
	public record Created(String checkoutUrl, Instant expiresAt) {
	}

	/**
	 * Immutable data carrier for session row.
	 */
	private record SessionRow(UUID paymentId, UUID merchantId, String returnUrl, Instant expiresAt,
			String checkoutStatus, String merchantOrderId, long amountMinor, String currency, String paymentStatus,
			String providerCode, String qr, String nextAction, String providerRedirectUrl) {
	}

	/**
	 * Applies payment expiry.
	 *
	 * @param merchantId       the merchant identifier
	 * @param paymentId        the payment identifier
	 * @param presentationMode the presentation mode value
	 * @return the resulting {@code Instant} value
	 */
	public Instant applyPaymentExpiry(UUID merchantId, UUID paymentId, String presentationMode) {
		Instant expiresAt = Instant.now().plusSeconds(ttlSeconds);
		paymentRpcService.configureCheckout(merchantId, paymentId, presentationMode, expiresAt.toEpochMilli());
		return expiresAt;
	}

	/**
	 * Executes the create operation.
	 *
	 * @param merchantId the merchant identifier
	 * @param paymentId  the payment identifier
	 * @param returnUrl  the merchant return URL
	 * @return the resulting {@code Created} value
	 */
	public Created create(UUID merchantId, UUID paymentId, String returnUrl) {
		if (returnUrl == null || returnUrl.isBlank()) {
			throw new IllegalArgumentException("returnUrl is required for HOSTED_PAGE");
		}
		Integer allowed = jdbc.queryForObject("""
				SELECT count(*) FROM merchant_redirect_uri
				WHERE merchant_id=? AND status='ACTIVE' AND redirect_uri=?
				""", Integer.class, merchantId, returnUrl.trim());
		if (allowed == null || allowed == 0) {
			throw new IllegalArgumentException("returnUrl is not registered for this merchant");
		}

		byte[] raw = new byte[32];
		random.nextBytes(raw);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
		String hash = Hashing.sha256(token);
		UUID sessionId = UUID.randomUUID();
		Instant expiresAt = Instant.now().plusSeconds(ttlSeconds);

		jdbc.update(
				"""
						INSERT INTO payment_checkout_session(checkout_session_id,payment_id,merchant_id,session_token_hash,return_url,status,expires_at)
						VALUES (?,?,?,?,?,'ACTIVE',?)
						""",
				sessionId, paymentId, merchantId, hash, returnUrl.trim(), Timestamp.from(expiresAt));
		try {
			// Remote call is deliberately outside a local DB transaction. Payment-core
			// remains the state authority.
			paymentRpcService.configureCheckout(merchantId, paymentId, "HOSTED_PAGE", expiresAt.toEpochMilli());
		} catch (RuntimeException e) {
			jdbc.update("DELETE FROM payment_checkout_session WHERE checkout_session_id=?", sessionId);
			throw e;
		}
		return new Created(publicBaseUrl + "/checkout/" + token, expiresAt);
	}

	/**
	 * Executes the get operation.
	 *
	 * @param token the authentication or verification token
	 * @return the resulting key-value data
	 */
	public Map<String, Object> get(String token) {
		String hash = Hashing.sha256(token);
		SessionRow row = load(hash);
		if (row == null)
			return null;

		String status = row.paymentStatus();
		boolean due = Instant.now().isAfter(row.expiresAt()) && !isTerminal(status);
		if (due) {
			PaymentView updated = paymentRpcService.expirePayment(row.merchantId(), row.paymentId());
			status = updated.status().name();
			if (updated.status() == PaymentStatus.EXPIRED)
				markSessionExpired(hash);
			else if (isTerminal(status))
				markSessionCompleted(hash);
		} else if (isTerminal(status) && "ACTIVE".equals(row.checkoutStatus())) {
			markSessionCompleted(hash);
		}

		boolean expired = "EXPIRED".equals(status);
		return Map.ofEntries(Map.entry("paymentId", row.paymentId().toString()),
				Map.entry("merchantOrderId", row.merchantOrderId()), Map.entry("amountMinor", row.amountMinor()),
				Map.entry("currency", row.currency()), Map.entry("status", status),
				Map.entry("providerCode", nullToEmpty(row.providerCode())),
				Map.entry("qr", expired ? "" : nullToEmpty(row.qr())),
				Map.entry("nextAction", nullToEmpty(row.nextAction())),
				Map.entry("providerRedirectUrl", nullToEmpty(row.providerRedirectUrl())),
				Map.entry("returnUrl", row.returnUrl()), Map.entry("expiresAt", row.expiresAt().toString()),
				Map.entry("serverTime", Instant.now().toString()));
	}

	/**
	 * Executes the load operation.
	 *
	 * @param hash the hash value
	 * @return the resulting {@code SessionRow} value
	 */
	private SessionRow load(String hash) {
		return jdbc.query(
				"""
						SELECT c.payment_id,c.merchant_id,c.return_url,c.status AS checkout_status,c.expires_at,
						       p.merchant_order_id,p.amount_minor,p.currency,p.status AS payment_status,p.selected_provider_code,
						       p.qr_payload,p.provider_next_action,p.provider_redirect_url
						FROM payment_checkout_session c JOIN payment p ON p.payment_id=c.payment_id
						WHERE c.session_token_hash=?
						""",
				rs -> {
					if (!rs.next())
						return null;
					return new SessionRow(rs.getObject("payment_id", UUID.class),
							rs.getObject("merchant_id", UUID.class), rs.getString("return_url"),
							rs.getTimestamp("expires_at").toInstant(), rs.getString("checkout_status"),
							rs.getString("merchant_order_id"), rs.getLong("amount_minor"), rs.getString("currency"),
							rs.getString("payment_status"), rs.getString("selected_provider_code"),
							rs.getString("qr_payload"), rs.getString("provider_next_action"),
							rs.getString("provider_redirect_url"));
				}, hash);
	}

	/**
	 * Expires due sessions.
	 */
	public void expireDueSessions() {
		var rows = jdbc.queryForList("""
				SELECT checkout_session_id,payment_id,merchant_id,session_token_hash
				FROM payment_checkout_session
				WHERE status='ACTIVE' AND expires_at <= now()
				ORDER BY expires_at
				LIMIT 500
				""");
		for (Map<String, Object> row : rows) {
			UUID paymentId = (UUID) row.get("payment_id");
			UUID merchantId = (UUID) row.get("merchant_id");
			String hash = String.valueOf(row.get("session_token_hash"));
			try {
				PaymentView updated = paymentRpcService.expirePayment(merchantId, paymentId);
				if (updated.status() == PaymentStatus.EXPIRED)
					markSessionExpired(hash);
				else if (isTerminal(updated.status().name()))
					markSessionCompleted(hash);
			} catch (RuntimeException ignored) {
				// Keep the session ACTIVE so the next scan retries. Payment-core is the
				// authority.
			}
		}
	}

	/**
	 * Executes the mark redirected operation.
	 *
	 * @param token the authentication or verification token
	 */
	@Transactional
	public void markRedirected(String token) {
		jdbc.update(
				"UPDATE payment_checkout_session SET redirected_at=now(), version=version+1, updated_at=now() WHERE session_token_hash=?",
				Hashing.sha256(token));
	}

	/**
	 * Executes the mark session expired operation.
	 *
	 * @param hash the hash value
	 */
	private void markSessionExpired(String hash) {
		jdbc.update("""
				UPDATE payment_checkout_session
				SET status='EXPIRED', completed_at=COALESCE(completed_at,now()), version=version+1, updated_at=now()
				WHERE session_token_hash=? AND status='ACTIVE'
				""", hash);
	}

	/**
	 * Executes the mark session completed operation.
	 *
	 * @param hash the hash value
	 */
	private void markSessionCompleted(String hash) {
		jdbc.update("""
				UPDATE payment_checkout_session
				SET status='COMPLETED', completed_at=COALESCE(completed_at,now()), version=version+1, updated_at=now()
				WHERE session_token_hash=? AND status='ACTIVE'
				""", hash);
	}

	/**
	 * Determines whether terminal.
	 *
	 * @param status the payment or delivery status
	 * @return {@code true} when the condition is satisfied; otherwise {@code false}
	 */
	private static boolean isTerminal(String status) {
		return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "EXPIRED".equals(status)
				|| "VOIDED".equals(status) || "REFUNDED".equals(status) || "PARTIALLY_REFUNDED".equals(status);
	}

	/**
	 * Executes the null to empty operation.
	 *
	 * @param value the value to process
	 * @return the resulting null to empty text
	 */
	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}
}
