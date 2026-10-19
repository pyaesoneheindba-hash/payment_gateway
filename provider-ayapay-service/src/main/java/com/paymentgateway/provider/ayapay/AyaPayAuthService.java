package com.paymentgateway.provider.ayapay;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.paymentgateway.audit.service.ProviderExchangeAuditService;
import com.paymentgateway.audit.util.AuditJson;
import com.paymentgateway.provider.ayapay.api.AyaLoginRequest;
import com.paymentgateway.provider.ayapay.api.AyaLoginResponse;
import com.paymentgateway.provider.ayapay.api.AyaTokenResponse;
import com.paymentgateway.provider.ayapay.http.AyaPayHttpService;

/**
 * Provides aya pay auth application services.
 */
@Component
public class AyaPayAuthService {
	/**
	 * Immutable data carrier for auth headers.
	 */
	public record AuthHeaders(String accessToken, String sessionToken) {
	}

	private final AyaPayHttpService http;
	private final AyaPayProperties props;
	private final ProviderExchangeAuditService audit;
	private String accessToken;
	private Instant accessExpiresAt = Instant.EPOCH;
	private String sessionToken;
	private Instant sessionExpiresAt = Instant.EPOCH;

	/**
	 * Creates a new {@code AyaPayAuthService} instance.
	 *
	 * @param http  the HTTP value
	 * @param props the props value
	 * @param audit the audit value
	 */
	public AyaPayAuthService(AyaPayHttpService http, AyaPayProperties props, ProviderExchangeAuditService audit) {
		this.http = http;
		this.props = props;
		this.audit = audit;
	}

	/**
	 * Executes the headers operation.
	 *
	 * @param paymentId the payment identifier
	 * @return the resulting {@code AuthHeaders} value
	 */
	public synchronized AuthHeaders headers(UUID paymentId) {
		Instant now = Instant.now().plusSeconds(30);
		if (accessToken == null || now.isAfter(accessExpiresAt))
			refreshAccessToken(paymentId);
		if (sessionToken == null || now.isAfter(sessionExpiresAt))
			refreshSessionToken(paymentId);
		return new AuthHeaders("Bearer " + accessToken, "Bearer " + sessionToken);
	}

	/**
	 * Executes the invalidate operation.
	 */
	public synchronized void invalidate() {
		accessToken = null;
		sessionToken = null;
		accessExpiresAt = Instant.EPOCH;
		sessionExpiresAt = Instant.EPOCH;
	}

	/**
	 * Refreshes access token.
	 *
	 * @param paymentId the payment identifier
	 */
	private void refreshAccessToken(UUID paymentId) {
		require(props.consumerKey(), "AYA_CONSUMER_KEY");
		require(props.consumerSecret(), "AYA_CONSUMER_SECRET");
		String basic = "Basic " + Base64.getEncoder()
				.encodeToString((props.consumerKey() + ":" + props.consumerSecret()).getBytes(StandardCharsets.UTF_8));
		UUID id = audit.start(paymentId, "AYAPAY", "GENERATE_TOKEN", null, null, "POST", props.baseUrl() + "/token",
				AuditJson.write(
						Map.of("Authorization", "***REDACTED***", "Content-Type", "application/x-www-form-urlencoded")),
				"grant_type=client_credentials");
		try {
			var response = http.generateToken(basic, "client_credentials");
			var body = response.getBody();
			var parsed = AuditJson.read(body, AyaTokenResponse.class);
			if (parsed == null || parsed.access_token() == null || parsed.access_token().isBlank())
				throw new IllegalStateException("AYA PAY token response has no access_token");
			accessToken = parsed.access_token();
			accessExpiresAt = Instant.now().plusSeconds(Math.max(60, parsed.expires_in()));
			audit.complete(id, response.getStatusCode().value(), AuditJson.write(response.getHeaders()),
					redactTokenBody(body), "200", "Token received", "RECEIVED");
		} catch (RuntimeException e) {
			audit.fail(id, null, null, null, e, "ERROR");
			throw e;
		}
	}

	/**
	 * Refreshes session token.
	 *
	 * @param paymentId the payment identifier
	 */
	private void refreshSessionToken(UUID paymentId) {
		require(props.merchantPhone(), "AYA_MERCHANT_PHONE");
		require(props.merchantPassword(), "AYA_MERCHANT_PASSWORD");
		UUID id = audit.start(paymentId, "AYAPAY", "MERCHANT_LOGIN", null, null, "POST",
				props.baseUrl() + "/merchant/1.0.0/thirdparty/merchant/login",
				AuditJson.write(Map.of("Token", "***REDACTED***")),
				AuditJson.write(Map.of("phone", maskPhone(props.merchantPhone()), "password", "***REDACTED***")));
		try {
			var response = http.merchantLogin("Bearer " + accessToken,
					new AyaLoginRequest(props.merchantPhone(), props.merchantPassword()));
			var body = response.getBody();
			var parsed = AuditJson.read(body, AyaLoginResponse.class);
			if (parsed == null || parsed.err() == null || parsed.err() != 200 || parsed.token() == null
					|| parsed.token().token() == null)
				throw new IllegalStateException(
						"AYA PAY merchant login failed: " + (parsed == null ? "empty" : parsed.message()));
			sessionToken = parsed.token().token();
			try {
				sessionExpiresAt = Instant.parse(parsed.token().expiredAt());
			} catch (Exception ignored) {
				sessionExpiresAt = Instant.now().plusSeconds(600);
			}
			audit.complete(id, response.getStatusCode().value(), AuditJson.write(response.getHeaders()),
					"{\"err\":200,\"message\":\"Success\",\"token\":\"***REDACTED***\"}", String.valueOf(parsed.err()),
					parsed.message(), "RECEIVED");
		} catch (RuntimeException e) {
			audit.fail(id, null, null, null, e, "ERROR");
			throw e;
		}
	}

	/**
	 * Executes the require operation.
	 *
	 * @param v   the v value
	 * @param env the env value
	 */
	private static void require(String v, String env) {
		if (v == null || v.isBlank())
			throw new IllegalStateException(env + " is required");
	}

	/**
	 * Executes the mask phone operation.
	 *
	 * @param p the payment entity
	 * @return the resulting mask phone text
	 */
	private static String maskPhone(String p) {
		if (p == null || p.length() < 4)
			return "***";
		return "***" + p.substring(p.length() - 4);
	}

	/**
	 * Executes the redact token body operation.
	 *
	 * @param body the request or response body
	 * @return the resulting redact token body text
	 */
	private static String redactTokenBody(String body) {
		return body == null ? null : "{\"tokenResponse\":\"***REDACTED***\"}";
	}
}
