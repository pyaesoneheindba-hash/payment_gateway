package com.paymentgateway.api.portal;

import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paymentgateway.api.webhook.WebhookUrlPolicy;

/**
 * Admin-controlled merchant onboarding workflow.
 *
 * Merchants are created PENDING and cannot use signed payment APIs until an administrator activates them. Only merchant
 * public keys are accepted; private keys must remain with the merchant.
 */
@Service
public class MerchantOnboardingService {
    private static final Pattern MERCHANT_CODE = Pattern.compile("[A-Za-z0-9_-]{2,64}");
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._:-]{1,100}");
    private static final Pattern PORTAL_USERNAME = Pattern.compile("[A-Za-z0-9._@+-]{2,120}");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final JdbcTemplate jdbc;
    private final RedirectUrlPolicy redirectUrlPolicy;
    private final WebhookUrlPolicy webhookUrlPolicy;
    private final KeycloakPortalIdentityProvisioner identityProvisioner;

    /**
     * Creates a new {@code MerchantOnboardingService} instance.
     *
     * @param jdbc                the jdbc value
     * @param redirectUrlPolicy   the redirect URL policy value
     * @param webhookUrlPolicy    the webhook URL policy value
     * @param identityProvisioner the identity provisioner value
     */
    public MerchantOnboardingService(JdbcTemplate jdbc, RedirectUrlPolicy redirectUrlPolicy,
            WebhookUrlPolicy webhookUrlPolicy, KeycloakPortalIdentityProvisioner identityProvisioner) {
        this.jdbc = jdbc;
        this.redirectUrlPolicy = redirectUrlPolicy;
        this.webhookUrlPolicy = webhookUrlPolicy;
        this.identityProvisioner = identityProvisioner;
    }

    /**
     * Carries input data for onboarding requests.
     */
    public record OnboardingRequest(
            String merchantCode,
            String merchantName,
            String environment,
            String contactEmail,
            String redirectUrl,
            String keyId,
            String publicKey,
            String webhookUrl,
            String webhookDescription,
            String portalEmail,
            String portalUsername,
            String portalDisplayName,
            String portalTemporaryPassword,
            Boolean portalRequirePasswordChange) {
    }

    /**
     * Represents the result of onboarding processing.
     */
    public record OnboardingResult(
            UUID merchantId,
            String merchantCode,
            String status,
            boolean redirectConfigured,
            boolean keyConfigured,
            boolean webhookConfigured,
            boolean portalAdminConfigured) {
    }

    /**
     * Executes the onboard operation.
     *
     * @param request the current request or request payload
     * @return the resulting {@code OnboardingResult} value
     */
    @Transactional
    public OnboardingResult onboard(OnboardingRequest request) {
        if (request == null)
            throw new IllegalArgumentException("Onboarding request is required");
        String code = trim(request.merchantCode());
        String name = trim(request.merchantName());
        if (code == null || !MERCHANT_CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("merchantCode must match [A-Za-z0-9_-]{2,64}");
        }
        if (name == null || name.length() > 200) {
            throw new IllegalArgumentException("merchantName is required and must be <= 200 characters");
        }
        String environment = request.environment() == null ? "UAT"
                : request.environment().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("UAT", "PRODUCTION").contains(environment)) {
            throw new IllegalArgumentException("environment must be UAT or PRODUCTION");
        }
        String email = trim(request.contactEmail());
        if (email != null && (email.length() > 255 || !EMAIL.matcher(email).matches())) {
            throw new IllegalArgumentException("contactEmail is invalid");
        }

        String redirect = trim(request.redirectUrl());
        if (redirect != null)
            redirect = redirectUrlPolicy.validateAndNormalize(redirect);

        String keyId = trim(request.keyId());
        String publicKey = trim(request.publicKey());
        boolean keyConfigured = keyId != null || publicKey != null;
        if (keyConfigured) {
            if (keyId == null || !KEY_ID.matcher(keyId).matches() || publicKey == null) {
                throw new IllegalArgumentException("keyId and publicKey must both be supplied");
            }
            validateEd25519PublicKey(publicKey);
        }

        String webhook = trim(request.webhookUrl());
        if (webhook != null)
            webhook = webhookUrlPolicy.validateAndNormalize(webhook);

        String portalEmail = trim(request.portalEmail());
        String portalUsername = trim(request.portalUsername());
        String portalDisplayName = trim(request.portalDisplayName());
        String portalTemporaryPassword = request.portalTemporaryPassword();
        boolean portalConfigured = portalEmail != null || portalUsername != null || portalDisplayName != null
                || (portalTemporaryPassword != null && !portalTemporaryPassword.isEmpty());
        boolean requirePasswordChange = request.portalRequirePasswordChange() == null
                || request.portalRequirePasswordChange();
        if (portalConfigured) {
            if (portalEmail == null || portalEmail.length() > 255 || !EMAIL.matcher(portalEmail).matches()) {
                throw new IllegalArgumentException(
                        "A valid portalEmail is required for the merchant portal administrator");
            }
            if (portalUsername == null || !PORTAL_USERNAME.matcher(portalUsername).matches()) {
                throw new IllegalArgumentException(
                        "A valid portalUsername is required for the merchant portal administrator");
            }
            if (portalDisplayName == null)
                portalDisplayName = name + " Administrator";
            if (portalDisplayName.length() > 200)
                throw new IllegalArgumentException("portalDisplayName is too long");
            if (portalTemporaryPassword == null || portalTemporaryPassword.isBlank()
                    || portalTemporaryPassword.length() < 12 || portalTemporaryPassword.length() > 200) {
                throw new IllegalArgumentException("portalTemporaryPassword must be between 12 and 200 characters");
            }
        }

        KeycloakPortalIdentityProvisioner.ProvisionedIdentity provisionedIdentity = null;
        if (portalConfigured) {
            try {
                provisionedIdentity = identityProvisioner.createMerchantAdmin(
                        new KeycloakPortalIdentityProvisioner.CreatePortalAdmin(
                                portalUsername, portalEmail, portalDisplayName, portalTemporaryPassword,
                                requirePasswordChange));
            } catch (KeycloakPortalIdentityProvisioner.IdentityConflictException e) {
                throw new MerchantOnboardingConflictException(e.getMessage(), e);
            }
        }

        UUID merchantId = UUID.randomUUID();
        try {
            jdbc.update(
                    """
                            INSERT INTO merchant(merchant_id,merchant_code,merchant_name,status,environment,default_currency,contact_email)
                            VALUES (?,?,?,'PENDING',?,'MMK',?)
                            """,
                    merchantId, code, name, environment, email);

            if (redirect != null) {
                jdbc.update("""
                        INSERT INTO merchant_redirect_uri(redirect_uri_id,merchant_id,redirect_uri,status)
                        VALUES (?,?,?,'ACTIVE')
                        """, UUID.randomUUID(), merchantId, redirect);
            }
            if (keyConfigured) {
                jdbc.update("""
                        INSERT INTO merchant_api_key(key_id,merchant_id,algorithm,public_key,status,valid_from)
                        VALUES (?,?,'ED25519',?,'ACTIVE',now())
                        """, keyId, merchantId, publicKey);
            }
            if (webhook != null) {
                jdbc.update("""
                        INSERT INTO merchant_webhook_endpoint(endpoint_id,merchant_id,event_type,url,description,status)
                        VALUES (?,?,'PAYMENT_STATUS_CHANGED',?,?, 'ACTIVE')
                        """, UUID.randomUUID(), merchantId, webhook, trim(request.webhookDescription()));
            }
            if (portalConfigured) {
                jdbc.update(
                        """
                                INSERT INTO portal_user(portal_user_id,username,display_name,role,merchant_id,status,oidc_subject)
                                VALUES (?,?,?,'MERCHANT_ADMIN',?,'ACTIVE',?)
                                """,
                        UUID.randomUUID(), portalUsername, portalDisplayName, merchantId,
                        provisionedIdentity.oidcSubject());
            }
        } catch (DataIntegrityViolationException e) {
            if (provisionedIdentity != null)
                identityProvisioner.deleteUserQuietly(provisionedIdentity.oidcSubject());
            throw new MerchantOnboardingConflictException(
                    "Merchant code, key ID, webhook, redirect URI, or portal identity already exists", e);
        } catch (RuntimeException e) {
            if (provisionedIdentity != null)
                identityProvisioner.deleteUserQuietly(provisionedIdentity.oidcSubject());
            throw e;
        }

        return new OnboardingResult(merchantId, code, "PENDING", redirect != null, keyConfigured,
                webhook != null, portalConfigured);
    }

    /**
     * Executes the readiness operation.
     *
     * @param merchantId the merchant identifier
     * @return the resulting key-value data
     */
    public Map<String, Object> readiness(UUID merchantId) {
        Map<String, Object> result = new LinkedHashMap<>(jdbc.queryForMap("""
                SELECT merchant_id,merchant_code,merchant_name,status,environment,contact_email
                FROM merchant WHERE merchant_id=?
                """, merchantId));
        Long keys = jdbc.queryForObject("SELECT count(*) FROM merchant_api_key WHERE merchant_id=? AND status='ACTIVE'",
                Long.class, merchantId);
        Long redirects = jdbc.queryForObject(
                "SELECT count(*) FROM merchant_redirect_uri WHERE merchant_id=? AND status='ACTIVE'", Long.class,
                merchantId);
        Long webhooks = jdbc.queryForObject(
                "SELECT count(*) FROM merchant_webhook_endpoint WHERE merchant_id=? AND status='ACTIVE'", Long.class,
                merchantId);
        Long portalAdmins = jdbc.queryForObject(
                "SELECT count(*) FROM portal_user WHERE merchant_id=? AND role='MERCHANT_ADMIN' AND status='ACTIVE'",
                Long.class, merchantId);
        result.put("activeSigningKeys", keys == null ? 0 : keys);
        result.put("activeRedirectUris", redirects == null ? 0 : redirects);
        result.put("activeWebhooks", webhooks == null ? 0 : webhooks);
        result.put("activeMerchantAdmins", portalAdmins == null ? 0 : portalAdmins);
        result.put("readyForActivation", keys != null && keys > 0);
        return result;
    }

    /**
     * Executes the activate operation.
     *
     * @param merchantId the merchant identifier
     * @return the resulting key-value data
     */
    @Transactional
    public Map<String, Object> activate(UUID merchantId) {
        String status = jdbc.queryForObject("SELECT status FROM merchant WHERE merchant_id=? FOR UPDATE", String.class,
                merchantId);
        if (status == null)
            throw new IllegalArgumentException("Merchant not found");
        if ("CLOSED".equals(status))
            throw new IllegalStateException("Closed merchants cannot be reactivated");
        Long keys = jdbc.queryForObject("SELECT count(*) FROM merchant_api_key WHERE merchant_id=? AND status='ACTIVE'",
                Long.class, merchantId);
        if (keys == null || keys < 1)
            throw new IllegalStateException("At least one ACTIVE Ed25519 public key is required before activation");
        jdbc.update("UPDATE merchant SET status='ACTIVE',version=version+1,updated_at=now() WHERE merchant_id=?",
                merchantId);
        return readiness(merchantId);
    }

    /**
     * Executes the suspend operation.
     *
     * @param merchantId the merchant identifier
     * @return the resulting key-value data
     */
    @Transactional
    public Map<String, Object> suspend(UUID merchantId) {
        int updated = jdbc.update("""
                UPDATE merchant SET status='SUSPENDED',version=version+1,updated_at=now()
                WHERE merchant_id=? AND status<>'CLOSED'
                """, merchantId);
        if (updated == 0)
            throw new IllegalArgumentException("Merchant not found or already closed");
        return readiness(merchantId);
    }

    /**
     * Validates ed25519 public key.
     *
     * @param pem the PEM value
     */
    private static void validateEd25519PublicKey(String pem) {
        try {
            String encoded = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(encoded);
            KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalArgumentException("publicKey must be a valid Ed25519 X.509 PEM public key", e);
        }
    }

    /**
     * Executes the trim operation.
     *
     * @param value the value to process
     * @return the resulting trim text
     */
    private static String trim(String value) {
        if (value == null)
            return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Signals a merchant onboarding conflict failure.
     */
    public static final class MerchantOnboardingConflictException extends RuntimeException {
        /**
         * Creates a new {@code MerchantOnboardingConflictException} instance.
         *
         * @param message the message value
         * @param cause   the cause value
         */
        public MerchantOnboardingConflictException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
