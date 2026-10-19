import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;

/**
 * Implements verify webhook signature behavior for the payment gateway platform.
 */
public class VerifyWebhookSignature {
    /**
     * Starts the verify webhook signature application.
     *
     * @param args the args value
     * @throws Exception if  processing fails
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 6) {
            System.err.println("Usage: java scripts/security/VerifyWebhookSignature.java <event-id> <timestamp> <content-digest> <signature-base64> <gateway-public-key-pem> <body-file>");
            System.exit(2);
        }
        UUID eventId = UUID.fromString(args[0]);
        String timestamp = args[1];
        String contentDigest = args[2];
        String signatureB64 = args[3];
        PublicKey publicKey = readPublicKey(Path.of(args[4]));
        byte[] body = Files.readAllBytes(Path.of(args[5]));

        String calculatedDigest = "sha-256=:" + Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(body)) + ":";
        if (!MessageDigest.isEqual(contentDigest.getBytes(StandardCharsets.US_ASCII), calculatedDigest.getBytes(StandardCharsets.US_ASCII))) {
            System.out.println("INVALID: Content-Digest does not match body");
            System.exit(1);
        }

        String canonical = String.join("\n", "PGWH1", eventId.toString(), timestamp, contentDigest);
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(canonical.getBytes(StandardCharsets.UTF_8));
        boolean valid = verifier.verify(Base64.getDecoder().decode(signatureB64));
        System.out.println(valid ? "VALID webhook signature" : "INVALID webhook signature");
        if (!valid) System.exit(1);
    }

    /**
     * Executes the read public key operation.
     *
     * @param pemFile the PEM file value
     * @return the resulting {@code PublicKey} value
     * @throws Exception if  processing fails
     */
    private static PublicKey readPublicKey(Path pemFile) throws Exception {
        String pem = Files.readString(pemFile, StandardCharsets.US_ASCII)
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] encoded = Base64.getDecoder().decode(pem);
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
    }
}
