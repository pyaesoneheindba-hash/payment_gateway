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

/**
 * Carries input data for sign HTTP requests.
 */
public class SignHttpRequest {
    /**
     * Starts the sign HTTP request application.
     *
     * @param args the args value
     * @throws Exception if  processing fails
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 9) {
            System.err.println("Usage: java scripts/security/SignHttpRequest.java <base-url> <method> <raw-path> <raw-query-or--> <merchant-uuid> <key-id> <private-key-pem> <idempotency-key-or--> <body-file-or-->");
            System.err.println("Example: java scripts/security/SignHttpRequest.java http://127.0.0.1:8086 POST /api/v1/payments - 11111111-1111-1111-1111-111111111111 merchant-key-2026-01 keys/merchant-key-2026-01-private.pem abank-test-001 abank-request.json");
            System.exit(2);
        }

        String baseUrl = stripTrailingSlash(args[0]);
        String method = args[1].toUpperCase();
        String rawPath = args[2];
        String rawQuery = dashToEmpty(args[3]);
        String merchantId = UUID.fromString(args[4]).toString();
        String keyId = args[5];
        Path privateKeyFile = Path.of(args[6]);
        String idempotencyKey = dashToEmpty(args[7]);
        String bodyFileArg = args[8];
        byte[] body = "-".equals(bodyFileArg) ? new byte[0] : Files.readAllBytes(Path.of(bodyFileArg));

        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        String contentDigest = "sha-256=:" + Base64.getEncoder().encodeToString(sha256(body)) + ":";
        String canonical = String.join("\n",
                "PGSIG1", method, rawPath, rawQuery, merchantId, keyId, timestamp,
                nonce, idempotencyKey, contentDigest);

        PrivateKey privateKey = readPrivateKey(privateKeyFile);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(canonical.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getEncoder().encodeToString(signer.sign());

        String fullUrl = baseUrl + rawPath + (rawQuery.isEmpty() ? "" : "?" + rawQuery);
        System.out.println("X-Merchant-Id: " + merchantId);
        System.out.println("X-Key-Id: " + keyId);
        System.out.println("X-Timestamp: " + timestamp);
        System.out.println("X-Nonce: " + nonce);
        if (!idempotencyKey.isEmpty()) System.out.println("Idempotency-Key: " + idempotencyKey);
        System.out.println("Content-Digest: " + contentDigest);
        System.out.println("X-Signature: " + signature);
        System.out.println();
        System.out.println("Windows CMD curl command:");
        StringBuilder curl = new StringBuilder();
        curl.append("curl.exe -i -X ").append(method).append(" \"").append(fullUrl).append("\"")
                .append(" -H \"X-Merchant-Id: ").append(merchantId).append("\"")
                .append(" -H \"X-Key-Id: ").append(keyId).append("\"")
                .append(" -H \"X-Timestamp: ").append(timestamp).append("\"")
                .append(" -H \"X-Nonce: ").append(nonce).append("\"");
        if (!idempotencyKey.isEmpty()) curl.append(" -H \"Idempotency-Key: ").append(idempotencyKey).append("\"");
        curl.append(" -H \"Content-Digest: ").append(contentDigest).append("\"")
                .append(" -H \"X-Signature: ").append(signature).append("\"");
        if (!"-".equals(bodyFileArg)) {
            curl.append(" -H \"Content-Type: application/json\" --data-binary \"@").append(bodyFileArg).append("\"");
        }
        System.out.println(curl);
        System.out.println();
        System.out.println("Canonical input (UTF-8; exact newlines are signed):");
        System.out.println(canonical);
    }

    /**
     * Executes the read private key operation.
     *
     * @param pemFile the PEM file value
     * @return the resulting {@code PrivateKey} value
     * @throws Exception if  processing fails
     */
    private static PrivateKey readPrivateKey(Path pemFile) throws Exception {
        String pem = Files.readString(pemFile, StandardCharsets.US_ASCII)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] encoded = Base64.getDecoder().decode(pem);
        return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(encoded));
    }

    /**
     * Executes the sha256 operation.
     *
     * @param body the request or response body
     * @return the resulting {@code byte[]} value
     * @throws Exception if  processing fails
     */
    private static byte[] sha256(byte[] body) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(body);
    }

    /**
     * Executes the dash to empty operation.
     *
     * @param value the value to process
     * @return the resulting dash to empty text
     */
    private static String dashToEmpty(String value) { return "-".equals(value) ? "" : value; }
    /**
     * Executes the strip trailing slash operation.
     *
     * @param value the value to process
     * @return the resulting strip trailing slash text
     */
    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
