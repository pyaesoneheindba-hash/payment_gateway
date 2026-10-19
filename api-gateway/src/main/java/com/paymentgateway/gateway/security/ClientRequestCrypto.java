package com.paymentgateway.gateway.security;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Implements client request crypto behavior for the payment gateway platform.
 */
public final class ClientRequestCrypto {
	public static final String ALGORITHM = "ED25519";
	private static final String CONTENT_DIGEST_PREFIX = "sha-256=:";

	/**
	 * Creates a new {@code ClientRequestCrypto} instance.
	 */
	private ClientRequestCrypto() {
	}

	/**
	 * Executes the sha256 operation.
	 *
	 * @param value the value to process
	 * @return the resulting {@code byte[]} value
	 */
	public static byte[] sha256(byte[] value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value);
		} catch (Exception e) {
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}

	/**
	 * Executes the sha256 hex operation.
	 *
	 * @param value the value to process
	 * @return the resulting sha256 hex text
	 */
	public static String sha256Hex(byte[] value) {
		return HexFormat.of().formatHex(sha256(value));
	}

	/**
	 * Executes the sha256 hex operation.
	 *
	 * @param value the value to process
	 * @return the resulting sha256 hex text
	 */
	public static String sha256Hex(String value) {
		return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Executes the content digest operation.
	 *
	 * @param body the request or response body
	 * @return the resulting content digest text
	 */
	public static String contentDigest(byte[] body) {
		return CONTENT_DIGEST_PREFIX + Base64.getEncoder().encodeToString(sha256(body)) + ":";
	}

	/**
	 * Verifies content digest.
	 *
	 * @param headerValue the header value value
	 * @param body        the request or response body
	 * @return {@code true} when the condition is satisfied; otherwise {@code false}
	 */
	public static boolean verifyContentDigest(String headerValue, byte[] body) {
		if (headerValue == null
				|| !headerValue.regionMatches(true, 0, CONTENT_DIGEST_PREFIX, 0, CONTENT_DIGEST_PREFIX.length())
				|| !headerValue.endsWith(":")) {
			return false;
		}
		String encoded = headerValue.substring(CONTENT_DIGEST_PREFIX.length(), headerValue.length() - 1);
		try {
			byte[] supplied = Base64.getDecoder().decode(encoded);
			return MessageDigest.isEqual(supplied, sha256(body));
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	/**
	 * Executes the canonicalize operation.
	 *
	 * @param method         the method value
	 * @param rawPath        the raw path value
	 * @param rawQuery       the raw query value
	 * @param merchantId     the merchant identifier
	 * @param keyId          the signing key identifier
	 * @param timestamp      the event or request timestamp
	 * @param nonce          the single-use nonce used for replay protection
	 * @param idempotencyKey the idempotency key used to deduplicate retries
	 * @param contentDigest  the content digest value
	 * @return the resulting canonicalize text
	 */
	public static String canonicalize(String method, String rawPath, String rawQuery, String merchantId, String keyId,
			String timestamp, String nonce, String idempotencyKey, String contentDigest) {
		return String.join("\n", "PGSIG1", safe(method).toUpperCase(), safe(rawPath), safe(rawQuery), safe(merchantId),
				safe(keyId), safe(timestamp), safe(nonce), safe(idempotencyKey), safe(contentDigest));
	}

	/**
	 * Verifies ed25519.
	 *
	 * @param publicKeyPemOrBase64 the public key PEM or base64 value
	 * @param canonical            the canonical value
	 * @param signatureBase64      the signature base64 value
	 * @return {@code true} when the condition is satisfied; otherwise {@code false}
	 */
	public static boolean verifyEd25519(String publicKeyPemOrBase64, String canonical, String signatureBase64) {
		try {
			PublicKey publicKey = parseEd25519PublicKey(publicKeyPemOrBase64);
			byte[] signatureBytes = Base64.getDecoder().decode(signatureBase64);
			Signature verifier = Signature.getInstance("Ed25519");
			verifier.initVerify(publicKey);
			verifier.update(canonical.getBytes(StandardCharsets.UTF_8));
			return verifier.verify(signatureBytes);
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Parses ed25519 public key.
	 *
	 * @param pemOrBase64 the PEM or base64 value
	 * @return the resulting {@code PublicKey} value
	 * @throws Exception if processing fails
	 */
	public static PublicKey parseEd25519PublicKey(String pemOrBase64) throws Exception {
		if (pemOrBase64 == null || pemOrBase64.isBlank()) {
			throw new IllegalArgumentException("Public key is empty");
		}
		String normalized = pemOrBase64.replace("-----BEGIN PUBLIC KEY-----", "")
				.replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
		byte[] encoded = Base64.getDecoder().decode(normalized);
		return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
	}

	/**
	 * Executes the safe operation.
	 *
	 * @param value the value to process
	 * @return the resulting safe text
	 */
	private static String safe(String value) {
		return value == null ? "" : value;
	}
}
