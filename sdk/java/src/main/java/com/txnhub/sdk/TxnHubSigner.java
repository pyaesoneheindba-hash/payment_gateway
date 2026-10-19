package com.txnhub.sdk;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Implements txn hub signer behavior for the payment gateway platform.
 */
final class TxnHubSigner {
    private final PrivateKey privateKey;

    /**
     * Creates a new {@code TxnHubSigner} instance.
     *
     * @param privateKeyPem the private key PEM value
     */
    TxnHubSigner(Path privateKeyPem) {
        try {
            String pem = Files.readString(privateKeyPem, StandardCharsets.US_ASCII)
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(pem);
            this.privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
        }
        catch (Exception e) { throw new TxnHubException("Could not load Ed25519 PKCS#8 private key", e); }
    }

    /**
     * Executes the content digest operation.
     *
     * @param body the request or response body
     * @return the resulting content digest text
     */
    static String contentDigest(byte[] body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
            return "sha-256=:" + Base64.getEncoder().encodeToString(digest) + ":";
        }
        catch (Exception e) { throw new TxnHubException("SHA-256 is unavailable", e); }
    }

    /**
     * Executes the sign operation.
     *
     * @param canonical the canonical value
     * @return the resulting sign text
     */
    String sign(String canonical) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey);
            signer.update(canonical.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        }
        catch (Exception e) { throw new TxnHubException("Could not sign TXN HUB request", e); }
    }

    /**
     * Executes the canonical operation.
     *
     * @param method the method value
     * @param rawPath the raw path value
     * @param rawQuery the raw query value
     * @param merchantId the merchant identifier
     * @param keyId the signing key identifier
     * @param timestamp the event or request timestamp
     * @param nonce the single-use nonce used for replay protection
     * @param idempotencyKey the idempotency key used to deduplicate retries
     * @param contentDigest the content digest value
     * @return the resulting canonical text
     */
    static String canonical(String method, String rawPath, String rawQuery, String merchantId,
                            String keyId, String timestamp, String nonce, String idempotencyKey,
                            String contentDigest) {
        return String.join("\n", "PGSIG1", method.toUpperCase(), rawPath,
                rawQuery == null ? "" : rawQuery,
                merchantId, keyId, timestamp, nonce,
                idempotencyKey == null ? "" : idempotencyKey, contentDigest);
    }
}
