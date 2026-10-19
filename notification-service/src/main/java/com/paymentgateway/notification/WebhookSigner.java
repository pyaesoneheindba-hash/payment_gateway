package com.paymentgateway.notification;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * Implements webhook signer behavior for the payment gateway platform.
 */
@Component
public class WebhookSigner {
	private final WebhookNotificationProperties properties;
	private volatile PrivateKey privateKey;

	/**
	 * Creates a new {@code WebhookSigner} instance.
	 *
	 * @param properties the properties value
	 */
	public WebhookSigner(WebhookNotificationProperties properties) {
		this.properties = properties;
	}

	/**
	 * Executes the sign operation.
	 *
	 * @param eventId the event identifier
	 * @param body    the request or response body
	 * @return the resulting {@code SignedWebhookHeaders} value
	 */
	public SignedWebhookHeaders sign(UUID eventId, byte[] body) {
		try {
			String timestamp = Long.toString(Instant.now().getEpochSecond());
			String contentDigest = "sha-256=:"
					+ Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body)) + ":";
			String canonical = String.join("\n", "PGWH1", eventId.toString(), timestamp, contentDigest);
			Signature signer = Signature.getInstance("Ed25519");
			signer.initSign(loadPrivateKey());
			signer.update(canonical.getBytes(StandardCharsets.UTF_8));
			String signature = Base64.getEncoder().encodeToString(signer.sign());
			return new SignedWebhookHeaders(properties.getSigningKeyId(), timestamp, contentDigest, signature);
		} catch (Exception e) {
			throw new IllegalStateException("Could not sign outbound payment webhook", e);
		}
	}

	/**
	 * Loads private key.
	 *
	 * @return the resulting {@code PrivateKey} value
	 * @throws Exception if processing fails
	 */
	private PrivateKey loadPrivateKey() throws Exception {
		PrivateKey current = privateKey;
		if (current != null)
			return current;
		synchronized (this) {
			if (privateKey != null)
				return privateKey;
			Path path = Path.of(properties.getPrivateKeyPath()).toAbsolutePath().normalize();
			String pem = Files.readString(path, StandardCharsets.US_ASCII).replace("-----BEGIN PRIVATE KEY-----", "")
					.replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
			byte[] encoded = Base64.getDecoder().decode(pem);
			privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(encoded));
			return privateKey;
		}
	}

	/**
	 * Immutable data carrier for signed webhook headers.
	 */
	public record SignedWebhookHeaders(String keyId, String timestamp, String contentDigest, String signature) {
	}
}
