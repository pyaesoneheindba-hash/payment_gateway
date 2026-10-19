import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Implements generate merchant ed25519 key pair behavior for the payment gateway platform.
 */
public class GenerateMerchantEd25519KeyPair {
    private static final Pattern SAFE_KEY_ID = Pattern.compile("[A-Za-z0-9._:-]{1,100}");

    /**
     * Starts the generate merchant ed25519 key pair application.
     *
     * @param args the args value
     * @throws Exception if  processing fails
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage: java scripts/security/GenerateMerchantEd25519KeyPair.java <merchant-uuid> <key-id> <output-dir>");
            System.exit(2);
        }
        UUID merchantId = UUID.fromString(args[0]);
        String keyId = args[1];
        if (!SAFE_KEY_ID.matcher(keyId).matches()) {
            throw new IllegalArgumentException("key-id must match " + SAFE_KEY_ID.pattern());
        }
        Path out = Path.of(args[2]).toAbsolutePath().normalize();
        Files.createDirectories(out);

        var generator = KeyPairGenerator.getInstance("Ed25519");
        var pair = generator.generateKeyPair();
        Path privateFile = out.resolve(keyId + "-private.pem");
        Path publicFile = out.resolve(keyId + "-public.pem");

        writeNew(privateFile, pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        writeNew(publicFile, pem("PUBLIC KEY", pair.getPublic().getEncoded()));
        try {
            Files.setPosixFilePermissions(privateFile, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
        catch (UnsupportedOperationException ignored) {
            // Windows/non-POSIX filesystem: set ACLs with the operating system if required.
        }

        String publicKeyBase64 = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        System.out.println("Generated Ed25519 key pair.");
        System.out.println("Private key (keep secret): " + privateFile);
        System.out.println("Public key:                " + publicFile);
        System.out.println();
        System.out.println("Run this SQL against payment_gateway (adjust validity dates as needed):");
        System.out.println("INSERT INTO merchant_api_key(key_id, merchant_id, algorithm, public_key, status, valid_from, valid_until)");
        System.out.println("VALUES ('" + keyId + "', '" + merchantId + "', 'ED25519', '" + publicKeyBase64
                + "', 'ACTIVE', now(), now() + interval '365 days')");
        System.out.println("ON CONFLICT (key_id) DO UPDATE SET merchant_id=EXCLUDED.merchant_id, algorithm=EXCLUDED.algorithm, public_key=EXCLUDED.public_key, status='ACTIVE', valid_from=EXCLUDED.valid_from, valid_until=EXCLUDED.valid_until;");
    }

    /**
     * Executes the write new operation.
     *
     * @param path the path value
     * @param content the content value
     * @throws Exception if  processing fails
     */
    private static void writeNew(Path path, String content) throws Exception {
        Files.writeString(path, content, StandardCharsets.US_ASCII, java.nio.file.StandardOpenOption.CREATE_NEW);
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
