import java.security.SecureRandom;
import java.util.Base64;

/**
 * Implements generate public token secret behavior for the payment gateway platform.
 */
public class GeneratePublicTokenSecret {
    /**
     * Starts the generate public token secret application.
     *
     * @param args the args value
     */
    public static void main(String[] args) {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        System.out.println(Base64.getEncoder().encodeToString(secret));
    }
}
