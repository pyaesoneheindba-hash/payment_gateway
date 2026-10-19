import java.util.Set;
import java.util.UUID;

/**
 * Prints SQL that binds an externally authenticated OIDC subject to local
 * payment-portal authorization. It does not create passwords or tokens.
 */
public class RegisterPortalOidcUser {
    private static final Set<String> ROLES = Set.of(
            "SUPER_ADMIN", "OPERATIONS", "FINANCE", "SUPPORT", "SECURITY_AUDITOR",
            "MERCHANT_ADMIN", "MERCHANT_DEVELOPER", "MERCHANT_FINANCE", "MERCHANT_VIEWER");

    /**
     * Starts the register portal OIDC user application.
     *
     * @param args the args value
     */
    public static void main(String[] args) {
        if (args.length < 4 || args.length > 5) {
            System.err.println("Usage: java scripts/security/RegisterPortalOidcUser.java <oidc-subject> <username> <displayName> <role> [merchantUuid]");
            System.exit(2);
        }
        String subject = required(args[0], "oidc-subject");
        String username = required(args[1], "username");
        String displayName = required(args[2], "displayName");
        String role = required(args[3], "role").toUpperCase();
        if (!ROLES.contains(role)) throw new IllegalArgumentException("Unsupported portal role: " + role);
        boolean merchantRole = role.startsWith("MERCHANT_");
        String merchantSql = "NULL";
        if (args.length == 5 && !args[4].isBlank()) merchantSql = q(UUID.fromString(args[4]).toString()) + "::uuid";
        if (merchantRole && "NULL".equals(merchantSql)) {
            throw new IllegalArgumentException("merchantUuid is required for MERCHANT_* roles");
        }
        if (!merchantRole && !"NULL".equals(merchantSql)) {
            throw new IllegalArgumentException("merchantUuid must be omitted for gateway admin roles");
        }
        System.out.println("INSERT INTO portal_user(portal_user_id,username,display_name,role,merchant_id,oidc_subject,status) VALUES (gen_random_uuid(),"
                + q(username) + "," + q(displayName) + "," + q(role) + "," + merchantSql + "," + q(subject) + ",'ACTIVE') "
                + "ON CONFLICT (username) DO UPDATE SET display_name=EXCLUDED.display_name,role=EXCLUDED.role,merchant_id=EXCLUDED.merchant_id,oidc_subject=EXCLUDED.oidc_subject,status='ACTIVE',updated_at=now();");
    }

    /**
     * Executes the required operation.
     *
     * @param value the value to process
     * @param name the name value
     * @return the resulting required text
     */
    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }

    /**
     * Executes the q operation.
     *
     * @param value the value to process
     * @return the resulting q text
     */
    private static String q(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
