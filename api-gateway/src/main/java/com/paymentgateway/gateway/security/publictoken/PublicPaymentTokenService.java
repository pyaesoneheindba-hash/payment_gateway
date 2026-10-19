package com.paymentgateway.gateway.security.publictoken;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Provides public payment token application services.
 */
@Service
public class PublicPaymentTokenService {
	private final PublicTokenSecurityProperties properties;

	/**
	 * Creates a new {@code PublicPaymentTokenService} instance.
	 *
	 * @param properties the properties value
	 */
	public PublicPaymentTokenService(PublicTokenSecurityProperties properties) {
		this.properties = properties;
	}

	/**
	 * Executes the issue operation.
	 *
	 * @param merchantId the merchant identifier
	 * @param request    the current request or request payload
	 * @return the resulting {@code PublicTokenIssueResponse} value
	 */
	public PublicTokenIssueResponse issue(UUID merchantId, PublicTokenIssueRequest request) {
		if (!properties.isEnabled())
			throw new PublicTokenException("PUBLIC_TOKEN_DISABLED", "Public token authentication is disabled");
		byte[] secret = signingSecret();
		ValidatedIssue validated = validateIssueRequest(request);
		Instant now = Instant.now();
		long ttl = properties.effectiveTtl(request.ttlSeconds() == null ? 0 : request.ttlSeconds());
		Instant expiresAt = now.plusSeconds(ttl);
		String jti = UUID.randomUUID().toString();

		JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder().issuer(properties.getIssuer())
				.audience(properties.getAudience()).subject(validated.subject()).jwtID(jti).issueTime(Date.from(now))
				.notBeforeTime(Date.from(now.minusSeconds(2))).expirationTime(Date.from(expiresAt))
				.claim("merchant_id", merchantId.toString()).claim("scope", List.copyOf(validated.scopes()))
				.claim("one_time", validated.oneTime());

		if (request.paymentId() != null)
			builder.claim("payment_id", request.paymentId().toString());
		if (notBlank(request.merchantOrderId()))
			builder.claim("merchant_order_id", request.merchantOrderId().trim());
		if (request.amountMinor() != null)
			builder.claim("amount_minor", request.amountMinor());
		if (notBlank(request.currency()))
			builder.claim("currency", request.currency().trim().toUpperCase(Locale.ROOT));
		if (notBlank(request.providerCode()))
			builder.claim("provider_code", request.providerCode().trim().toUpperCase(Locale.ROOT));
		if (notBlank(request.customerPhone()))
			builder.claim("customer_phone", request.customerPhone().trim());

		try {
			SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(),
					builder.build());
			jwt.sign(new MACSigner(secret));
			return new PublicTokenIssueResponse(jwt.serialize(), "Bearer", ttl, expiresAt, jti, validated.scopes());
		} catch (JOSEException e) {
			throw new PublicTokenException("PUBLIC_TOKEN_SIGNING_FAILED", "Could not sign public payment token", e);
		}
	}

	/**
	 * Executes the validate operation.
	 *
	 * @param compactJwt the compact JWT value
	 * @return the resulting {@code PublicTokenClaims} value
	 */
	public PublicTokenClaims validate(String compactJwt) {
		if (!properties.isEnabled())
			throw new PublicTokenException("PUBLIC_TOKEN_DISABLED", "Public token authentication is disabled");
		if (compactJwt == null || compactJwt.isBlank() || compactJwt.length() > 8192) {
			throw new PublicTokenException("INVALID_PUBLIC_TOKEN", "Bearer token is missing or malformed");
		}

		byte[] secret = signingSecret();
		try {
			SignedJWT jwt = SignedJWT.parse(compactJwt);
			if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())) {
				throw new PublicTokenException("INVALID_PUBLIC_TOKEN_ALGORITHM", "Public token must use HS256");
			}
			if (!jwt.verify(new MACVerifier(secret))) {
				throw new PublicTokenException("INVALID_PUBLIC_TOKEN_SIGNATURE", "Public token signature is invalid");
			}

			JWTClaimsSet claims = jwt.getJWTClaimsSet();
			Instant now = Instant.now();
			long skew = Math.max(0, properties.getAllowedClockSkewSeconds());
			if (!properties.getIssuer().equals(claims.getIssuer())) {
				throw new PublicTokenException("INVALID_PUBLIC_TOKEN_ISSUER", "Public token issuer is invalid");
			}
			if (claims.getAudience() == null || !claims.getAudience().contains(properties.getAudience())) {
				throw new PublicTokenException("INVALID_PUBLIC_TOKEN_AUDIENCE", "Public token audience is invalid");
			}
			if (claims.getExpirationTime() == null
					|| now.isAfter(claims.getExpirationTime().toInstant().plusSeconds(skew))) {
				throw new PublicTokenException("PUBLIC_TOKEN_EXPIRED", "Public token has expired");
			}
			if (claims.getNotBeforeTime() != null
					&& now.plusSeconds(skew).isBefore(claims.getNotBeforeTime().toInstant())) {
				throw new PublicTokenException("PUBLIC_TOKEN_NOT_YET_VALID", "Public token is not yet valid");
			}
			if (claims.getIssueTime() == null || claims.getJWTID() == null || claims.getJWTID().isBlank()) {
				throw new PublicTokenException("INVALID_PUBLIC_TOKEN",
						"Public token is missing required registered claims");
			}
			Instant issuedAt = claims.getIssueTime().toInstant();
			if (issuedAt.isAfter(now.plusSeconds(skew))) {
				throw new PublicTokenException("INVALID_PUBLIC_TOKEN_IAT",
						"Public token issued-at time is in the future");
			}
			long maxLifetime = Math.max(30, properties.getMaxTtlSeconds()) + skew;
			if (claims.getExpirationTime().toInstant().isAfter(issuedAt.plusSeconds(maxLifetime))) {
				throw new PublicTokenException("INVALID_PUBLIC_TOKEN_LIFETIME",
						"Public token lifetime exceeds gateway policy");
			}

			UUID merchantId = UUID.fromString(claims.getStringClaim("merchant_id"));
			List<String> scopeClaim = claims.getStringListClaim("scope");
			Set<String> scopes = scopeClaim == null ? Set.of() : new LinkedHashSet<>(scopeClaim);
			if (scopes.isEmpty() || !properties.getAllowedScopes().containsAll(scopes)) {
				throw new PublicTokenException("INVALID_PUBLIC_TOKEN_SCOPE",
						"Public token contains unsupported scopes");
			}

			return new PublicTokenClaims(merchantId, claims.getSubject(), claims.getJWTID(), Set.copyOf(scopes),
					claims.getIssueTime().toInstant(),
					claims.getNotBeforeTime() == null ? null : claims.getNotBeforeTime().toInstant(),
					claims.getExpirationTime().toInstant(), parseUuidClaim(claims, "payment_id"),
					claims.getStringClaim("merchant_order_id"), numberClaim(claims, "amount_minor"),
					claims.getStringClaim("currency"), claims.getStringClaim("provider_code"),
					claims.getStringClaim("customer_phone"), Boolean.TRUE.equals(claims.getBooleanClaim("one_time")));
		} catch (PublicTokenException e) {
			throw e;
		} catch (ParseException | JOSEException | IllegalArgumentException e) {
			throw new PublicTokenException("INVALID_PUBLIC_TOKEN", "Bearer token is malformed or invalid", e);
		}
	}

	/**
	 * Validates issue request.
	 *
	 * @param request the current request or request payload
	 * @return the resulting {@code ValidatedIssue} value
	 */
	private ValidatedIssue validateIssueRequest(PublicTokenIssueRequest request) {
		if (request == null)
			throw new PublicTokenException("INVALID_TOKEN_REQUEST", "Token request body is required");
		String subject = notBlank(request.subject()) ? request.subject().trim() : "public-checkout";
		if (subject.length() > 200)
			throw new PublicTokenException("INVALID_TOKEN_REQUEST", "subject is too long");
		Set<String> scopes = request.scopes() == null ? Set.of() : new LinkedHashSet<>(request.scopes());
		scopes.removeIf(scope -> scope == null || scope.isBlank());
		if (scopes.isEmpty())
			throw new PublicTokenException("INVALID_TOKEN_SCOPE", "At least one scope is required");
		if (!properties.getAllowedScopes().containsAll(scopes)) {
			throw new PublicTokenException("INVALID_TOKEN_SCOPE", "Requested public token scope is not allowed");
		}

		boolean hasWrite = scopes.stream()
				.anyMatch(scope -> !"payment:read".equals(scope) && !"payment:status".equals(scope));
		boolean oneTime = request.oneTime() == null ? hasWrite : request.oneTime();
		if (hasWrite && !oneTime) {
			throw new PublicTokenException("INVALID_TOKEN_REQUEST", "Public write tokens must be one-time tokens");
		}
		if (scopes.contains("payment:read") && request.paymentId() == null) {
			throw new PublicTokenException("INVALID_TOKEN_REQUEST", "payment:read token requires paymentId binding");
		}
		if (scopes.contains("payment:status") && request.paymentId() == null && !notBlank(request.merchantOrderId())) {
			throw new PublicTokenException("INVALID_TOKEN_REQUEST",
					"payment:status token requires paymentId or merchantOrderId binding");
		}
		if (scopes.contains("payment:create") || scopes.contains("customer-qr:pay")) {
			if (!notBlank(request.merchantOrderId()) || request.amountMinor() == null || request.amountMinor() <= 0
					|| !notBlank(request.currency()) || !notBlank(request.providerCode())) {
				throw new PublicTokenException("INVALID_TOKEN_REQUEST",
						"Public payment write token requires merchantOrderId, amountMinor, currency and providerCode bindings");
			}
		}
		if (scopes.contains("customer-qr:pay") && !notBlank(request.customerPhone())) {
			throw new PublicTokenException("INVALID_TOKEN_REQUEST",
					"customer-qr:pay token requires customerPhone binding");
		}
		return new ValidatedIssue(subject, Set.copyOf(scopes), oneTime);
	}

	/**
	 * Executes the signing secret operation.
	 *
	 * @return the resulting {@code byte[]} value
	 */
	private byte[] signingSecret() {
		String encoded = properties.getHmacSecretBase64();
		if (!notBlank(encoded) && notBlank(properties.getHmacSecretFile())) {
			try {
				encoded = Files.readString(Path.of(properties.getHmacSecretFile()).toAbsolutePath().normalize(),
						StandardCharsets.US_ASCII).trim();
			} catch (Exception e) {
				throw new PublicTokenException("PUBLIC_TOKEN_SIGNING_KEY_NOT_CONFIGURED",
						"Public token signing-key file could not be read", e);
			}
		}
		if (!notBlank(encoded)) {
			throw new PublicTokenException("PUBLIC_TOKEN_SIGNING_KEY_NOT_CONFIGURED",
					"Configure payment.security.public-token.hmac-secret-base64 or hmac-secret-file before public tokens can be used");
		}
		try {
			byte[] secret = Base64.getDecoder().decode(encoded.trim());
			if (secret.length < 32) {
				throw new PublicTokenException("PUBLIC_TOKEN_SIGNING_KEY_TOO_SHORT",
						"Public token HS256 key must be at least 256 bits");
			}
			return secret;
		} catch (IllegalArgumentException e) {
			throw new PublicTokenException("PUBLIC_TOKEN_SIGNING_KEY_INVALID",
					"Public token signing key is not valid Base64", e);
		}
	}

	/**
	 * Parses uuid claim.
	 *
	 * @param claims the claims value
	 * @param name   the name value
	 * @return the resulting {@code UUID} value
	 * @throws ParseException if parse processing fails
	 */
	private static UUID parseUuidClaim(JWTClaimsSet claims, String name) throws ParseException {
		String value = claims.getStringClaim(name);
		return value == null || value.isBlank() ? null : UUID.fromString(value);
	}

	/**
	 * Executes the number claim operation.
	 *
	 * @param claims the claims value
	 * @param name   the name value
	 * @return the resulting number claim value
	 * @throws ParseException if parse processing fails
	 */
	private static Long numberClaim(JWTClaimsSet claims, String name) throws ParseException {
		Object value = claims.getClaim(name);
		if (value == null)
			return null;
		if (value instanceof Number number)
			return number.longValue();
		return Long.valueOf(String.valueOf(value));
	}

	/**
	 * Executes the not blank operation.
	 *
	 * @param value the value to process
	 * @return {@code true} when the condition is satisfied; otherwise {@code false}
	 */
	private static boolean notBlank(String value) {
		return value != null && !value.isBlank();
	}

	/**
	 * Immutable data carrier for validated issue.
	 */
	private record ValidatedIssue(String subject, Set<String> scopes, boolean oneTime) {
	}
}
