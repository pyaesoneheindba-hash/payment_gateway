import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Implements generate gateway webhook key pair behavior for the payment gateway platform.
 */
public class GenerateGatewayWebhookKeyPair {
    private static final Pattern SAFE_KEY_ID = Pattern.compile("[A-Za-z0-9._:-]{1,100}");

    /**
     * Starts the generate gateway webhook key pair application.
     *
     * @param args the args value
     * @throws Exception if  processing fails
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: java scripts/security/GenerateGatewayWebhookKeyPair.java <key-id> <output-dir>");
            System.exit(2);
        }
        String keyId = args[0];
        if (!SAFE_KEY_ID.matcher(keyId).matches()) throw new IllegalArgumentException("Invalid key-id");
        Path out = Path.of(args[1]).toAbsolutePath().normalize();
        Files.createDirectories(out);

        var generator = KeyPairGenerator.getInstance("Ed25519");
        var pair = generator.generateKeyPair();
        Path privateFile = out.resolve(keyId + "-private.pem");
        Path publicFile = out.resolve(keyId + "-public.pem");
        Files.writeString(privateFile, pem("PRIVATE KEY", pair.getPrivate().getEncoded()), StandardCharsets.US_ASCII,
                java.nio.file.StandardOpenOption.CREATE_NEW);
        Files.writeString(publicFile, pem("PUBLIC KEY", pair.getPublic().getEncoded()), StandardCharsets.US_ASCII,
                java.nio.file.StandardOpenOption.CREATE_NEW);
        try {
            Files.setPosixFilePermissions(privateFile, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
        catch (UnsupportedOperationException ignored) {}

        System.out.println("Generated Payment Gateway outbound webhook Ed25519 key pair.");
        System.out.println("Key ID:      " + keyId);
        System.out.println("Private key: " + privateFile + "  <-- keep only on Payment Gateway notification-service");
        System.out.println("Public key:  " + publicFile + "  <-- distribute to trusted merchants");
        System.out.println("Public key (Base64 DER): " + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
    }

    /**
     * Executes the PEM operation.
     *
     * @param label the label value
     * @param encoded the encoded value
     * @return the resulting PEM text
     */
    private static String pem(String label, byte[] encoded) {
        String b64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded);
        return "-----BEGIN " + label + "-----\n" + b64 + "\n-----END " + label + "-----\n";
    }
}
