package com.paymentgateway.api.portal;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repairs the local merchant-admin authorization binding when a Keycloak user
 * has been lost/recreated, and resets credentials when the identity still
 * exists. This is intentionally SUPER_ADMIN-only at the controller boundary.
 */
@Service
public class MerchantPortalLoginRepairService {
    private static final Pattern PORTAL_USERNAME = Pattern.compile("[A-Za-z0-9._@+-]{2,120}");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final JdbcTemplate jdbc;
    private final KeycloakPortalIdentityProvisioner identityProvisioner;

    /**
     * Creates a new {@code MerchantPortalLoginRepairService} instance.
     *
     * @param jdbc the jdbc value
     * @param identityProvisioner the identity provisioner value
     */
    public MerchantPortalLoginRepairService(
            JdbcTemplate jdbc,
            KeycloakPortalIdentityProvisioner identityProvisioner) {
        this.jdbc = jdbc;
        this.identityProvisioner = identityProvisioner;
    }

    /**
     * Carries input data for repair requests.
     */
    public record RepairRequest(
            String username,
            String email,
            String displayName,
            String temporaryPassword,
            Boolean requirePasswordChange) {}

    /**
     * Represents the result of repair processing.
     */
    public record RepairResult(
            UUID merchantId,
            UUID portalUserId,
            String username,
            String oidcSubject,
            String action,
            boolean requirePasswordChange) {}

    /**
     * Executes the repair operation.
     *
     * @param merchantId the merchant identifier
     * @param request the current request or request payload
     * @return the resulting {@code RepairResult} value
     */
    @Transactional
    public RepairResult repair(UUID merchantId, RepairRequest request) {
        if (request == null) throw new IllegalArgumentException("Repair request is required");
        String username = trim(request.username());
        String email = trim(request.email());
        String displayName = trim(request.displayName());
        String password = request.temporaryPassword();
        boolean requirePasswordChange = request.requirePasswordChange() == null || request.requirePasswordChange();

        if (username == null || !PORTAL_USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException("username must match [A-Za-z0-9._@+-]{2,120}");
        }
        if (email == null || email.length() > 255 || !EMAIL.matcher(email).matches()) {
            throw new IllegalArgumentException("A valid email is required");
        }
        if (displayName == null || displayName.length() > 200) {
            throw new IllegalArgumentException("displayName is required and must be <= 200 characters");
        }
        if (password == null || password.isBlank() || password.length() < 12 || password.length() > 200) {
            throw new IllegalArgumentException("temporaryPassword must be between 12 and 200 characters");
        }

        Map<String, Object> merchant = jdbc.queryForMap(
                "SELECT merchant_id,merchant_name,status FROM merchant WHERE merchant_id=? FOR UPDATE", merchantId);
        String merchantStatus = String.valueOf(merchant.get("status")).toUpperCase(Locale.ROOT);
        if ("CLOSED".equals(merchantStatus)) {
            throw new IllegalStateException("Closed merchants cannot have portal access repaired");
        }

        List<Map<String, Object>> admins = jdbc.queryForList("""
            SELECT portal_user_id,username,display_name,status,oidc_subject
            FROM portal_user
            WHERE merchant_id=? AND role='MERCHANT_ADMIN'
            ORDER BY CASE WHEN status='ACTIVE' THEN 0 ELSE 1 END, created_at
            LIMIT 1
            """, merchantId);

        UUID portalUserId = admins.isEmpty() ? UUID.randomUUID() : (UUID) admins.getFirst().get("portal_user_id");
        String existingSubject = admins.isEmpty() ? null : (String) admins.getFirst().get("oidc_subject");

        Long usernameConflicts = jdbc.queryForObject("""
            SELECT count(*) FROM portal_user
            WHERE lower(username)=lower(?) AND portal_user_id<>?
            """, Long.class, username, portalUserId);
        if (usernameConflicts != null && usernameConflicts > 0) {
            throw new RepairConflictException("Portal username is already assigned to another local portal user");
        }

        var keycloakRequest = new KeycloakPortalIdentityProvisioner.CreatePortalAdmin(
                username, email, displayName, password, requirePasswordChange);

        if (existingSubject != null && !existingSubject.isBlank() && identityProvisioner.userExists(existingSubject)) {
            identityProvisioner.repairExistingMerchantAdmin(existingSubject, keycloakRequest);
            try {
                jdbc.update("""
                    UPDATE portal_user
                    SET username=?,display_name=?,status='ACTIVE',updated_at=now(),version=version+1
                    WHERE portal_user_id=?
                    """, username, displayName, portalUserId);
            }
            catch (DataIntegrityViolationException e) {
                throw new RepairConflictException("Portal username conflicts with another local portal user", e);
            }
            return new RepairResult(merchantId, portalUserId, username, existingSubject,
                    "RESET_EXISTING_IDENTITY", requirePasswordChange);
        }

        KeycloakPortalIdentityProvisioner.ProvisionedIdentity created = null;
        try {
            created = identityProvisioner.createMerchantAdmin(keycloakRequest);
            if (admins.isEmpty()) {
                jdbc.update("""
                    INSERT INTO portal_user(portal_user_id,username,display_name,role,merchant_id,status,oidc_subject)
                    VALUES (?,?,?,'MERCHANT_ADMIN',?,'ACTIVE',?)
                    """, portalUserId, username, displayName, merchantId, created.oidcSubject());
            }
            else {
                jdbc.update("""
                    UPDATE portal_user
                    SET username=?,display_name=?,status='ACTIVE',oidc_subject=?,updated_at=now(),version=version+1
                    WHERE portal_user_id=?
                    """, username, displayName, created.oidcSubject(), portalUserId);
            }
            return new RepairResult(merchantId, portalUserId, username, created.oidcSubject(),
                    "RECREATED_MISSING_IDENTITY", requirePasswordChange);
        }
        catch (KeycloakPortalIdentityProvisioner.IdentityConflictException e) {
            throw new RepairConflictException(e.getMessage(), e);
        }
        catch (DataIntegrityViolationException e) {
            if (created != null) identityProvisioner.deleteUserQuietly(created.oidcSubject());
            throw new RepairConflictException("Portal username or OIDC subject conflicts with an existing local portal user", e);
        }
        catch (RuntimeException e) {
            if (created != null) identityProvisioner.deleteUserQuietly(created.oidcSubject());
            throw e;
        }
    }

    /**
     * Executes the trim operation.
     *
     * @param value the value to process
     * @return the resulting trim text
     */
    private static String trim(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Signals a repair conflict failure.
     */
    public static final class RepairConflictException extends RuntimeException {
        /**
         * Creates a new {@code RepairConflictException} instance.
         *
         * @param message the message value
         */
        public RepairConflictException(String message) { super(message); }
        /**
         * Creates a new {@code RepairConflictException} instance.
         *
         * @param message the message value
         * @param cause the cause value
         */
        public RepairConflictException(String message, Throwable cause) { super(message, cause); }
    }
}
