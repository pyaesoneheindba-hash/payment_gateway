package com.txnhub.sdk;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

/**
 * Implements txn hub webhook verifier behavior for the payment gateway platform.
 */
public final class TxnHubWebhookVerifier {
    private final PublicKey publicKey;
    private final long allowedClockSkewSeconds;

    /**
     * Creates a new {@code TxnHubWebhookVerifier} instance.
     *
     * @param gatewayPublicKeyPem the gateway public key PEM value
     * @param allowedClockSkewSeconds the allowed clock skew seconds value
     */
    public TxnHubWebhookVerifier(Path gatewayPublicKeyPem, long allowedClockSkewSeconds) {
        this.allowedClockSkewSeconds = allowedClockSkewSeconds;
        try {
            String pem = Files.readString(gatewayPublicKeyPem, StandardCharsets.US_ASCII)
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            this.publicKey = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
        }
        catch (Exception e) { throw new TxnHubException("Could not load TXN HUB webhook public key", e); }
    }

    /**
     * Executes the verify operation.
     *
     * @param eventId the event identifier
     * @param timestamp the event or request timestamp
     * @param contentDigest the content digest value
     * @param signatureBase64 the signature base64 value
     * @param body the request or response body
     * @return {@code true} when the condition is satisfied; otherwise {@code false}
     */
    public boolean verify(String eventId, String timestamp, String contentDigest, String signatureBase64, byte[] body) {
        try {
            long ts = Long.parseLong(timestamp);
            if (Math.abs(Instant.now().getEpochSecond() - ts) > allowedClockSkewSeconds) return false;
            String calculated = TxnHubSigner.contentDigest(body);
            if (!MessageDigest.isEqual(calculated.getBytes(StandardCharsets.US_ASCII), contentDigest.getBytes(StandardCharsets.US_ASCII))) return false;
            String canonical = String.join("\n", "PGWH1", eventId, timestamp, contentDigest);
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(canonical.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signatureBase64));
        }
        catch (Exception e) { return false; }
    }
}
