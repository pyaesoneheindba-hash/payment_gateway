package com.paymentgateway.api.portal;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Provisions and repairs merchant-portal users in Keycloak through a
 * least-privilege service-account client. Passwords are sent only to Keycloak
 * and are never persisted in the Payment Gateway database.
 */
@Component
public class KeycloakPortalIdentityProvisioner {
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String adminBaseUrl;
    private final String realm;
    private final String clientId;
    private final String clientSecret;

    /**
     * Creates a new {@code KeycloakPortalIdentityProvisioner} instance.
     *
     * @param mapper the object mapper used to serialize and deserialize JSON
     * @param adminBaseUrl the admin base URL value
     * @param realm the realm value
     * @param clientId the client identifier value
     * @param clientSecret the client secret value
     */
    public KeycloakPortalIdentityProvisioner(
            ObjectMapper mapper,
            @Value("${payment.identity.keycloak.admin-base-url:}") String adminBaseUrl,
            @Value("${payment.identity.keycloak.realm:payment-gateway}") String realm,
            @Value("${payment.identity.keycloak.client-id:}") String clientId,
            @Value("${payment.identity.keycloak.client-secret:}") String clientSecret) {
        this.mapper = mapper;
        this.adminBaseUrl = stripTrailingSlash(adminBaseUrl);
        this.realm = realm == null ? "" : realm.trim();
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Immutable data carrier for create portal admin.
     */
    public record CreatePortalAdmin(
            String username,
            String email,
            String displayName,
            String temporaryPassword,
            boolean requirePasswordChange) {}

    /**
     * Immutable data carrier for provisioned identity.
     */
    public record ProvisionedIdentity(String oidcSubject, String username, String email) {}

    /**
     * Creates merchant admin.
     *
     * @param request the current request or request payload
     * @return the resulting {@code ProvisionedIdentity} value
     */
    public ProvisionedIdentity createMerchantAdmin(CreatePortalAdmin request) {
        requireConfigured();
        String token = serviceAccountToken();

        Map<String, Object> credential = passwordCredential(request.temporaryPassword(), request.requirePasswordChange());

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("username", request.username());
        user.put("email", request.email());
        user.put("enabled", true);
        user.put("emailVerified", false);
        user.put("firstName", request.displayName());
        user.put("credentials", List.of(credential));
        if (request.requirePasswordChange()) {
            user.put("requiredActions", List.of("UPDATE_PASSWORD"));
        }

        HttpRequest httpRequest;
        try {
            httpRequest = HttpRequest.newBuilder()
                    .uri(usersUri())
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(user)))
                    .build();
        }
        catch (Exception e) {
            throw new IdentityProvisioningException("Could not build Keycloak user-provisioning request", e);
        }

        try {
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 409) {
                throw new IdentityConflictException("Merchant portal username or email already exists in Keycloak");
            }
            if (response.statusCode() != 201) {
                throw new IdentityProvisioningException("Keycloak user creation failed with HTTP " + response.statusCode());
            }
            String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> new IdentityProvisioningException("Keycloak user creation response did not include Location"));
            String subject = lastPathSegment(location);
            if (subject.isBlank()) throw new IdentityProvisioningException("Keycloak returned an empty user ID");
            return new ProvisionedIdentity(subject, request.username(), request.email());
        }
        catch (IdentityProvisioningException e) {
            throw e;
        }
        catch (Exception e) {
            throw new IdentityProvisioningException("Could not create merchant portal user in Keycloak", e);
        }
    }

    /** Returns false only when Keycloak explicitly reports that the subject is absent. */
    public boolean userExists(String oidcSubject) {
        if (oidcSubject == null || oidcSubject.isBlank()) return false;
        requireConfigured();
        String token = serviceAccountToken();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(userUri(oidcSubject))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) return true;
            if (response.statusCode() == 404) return false;
            throw new IdentityProvisioningException("Keycloak user lookup failed with HTTP " + response.statusCode());
        }
        catch (IdentityProvisioningException e) {
            throw e;
        }
        catch (Exception e) {
            throw new IdentityProvisioningException("Could not look up merchant portal user in Keycloak", e);
        }
    }

    /**
     * Updates the non-secret profile and sets a fresh temporary/permanent password
     * for an existing Keycloak user. No password material is returned or stored.
     */
    public void repairExistingMerchantAdmin(String oidcSubject, CreatePortalAdmin request) {
        requireConfigured();
        if (oidcSubject == null || oidcSubject.isBlank()) {
            throw new IdentityProvisioningException("Cannot repair a Keycloak user without an OIDC subject");
        }
        String token = serviceAccountToken();

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("username", request.username());
        profile.put("email", request.email());
        profile.put("enabled", true);
        profile.put("emailVerified", false);
        profile.put("firstName", request.displayName());

        try {
            HttpRequest update = HttpRequest.newBuilder()
                    .uri(userUri(oidcSubject))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(profile)))
                    .build();
            HttpResponse<String> updated = http.send(update, HttpResponse.BodyHandlers.ofString());
            if (updated.statusCode() == 409) {
                throw new IdentityConflictException("Merchant portal username or email already exists in Keycloak");
            }
            if (updated.statusCode() == 404) {
                throw new IdentityMissingException("Merchant portal user no longer exists in Keycloak");
            }
            if (updated.statusCode() != 204) {
                throw new IdentityProvisioningException("Keycloak user profile update failed with HTTP " + updated.statusCode());
            }

            HttpRequest password = HttpRequest.newBuilder()
                    .uri(URI.create(userUri(oidcSubject).toString() + "/reset-password"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                            passwordCredential(request.temporaryPassword(), request.requirePasswordChange()))))
                    .build();
            HttpResponse<String> reset = http.send(password, HttpResponse.BodyHandlers.ofString());
            if (reset.statusCode() == 404) {
                throw new IdentityMissingException("Merchant portal user disappeared from Keycloak while its password was being reset");
            }
            if (reset.statusCode() != 204) {
                throw new IdentityProvisioningException("Keycloak password reset failed with HTTP " + reset.statusCode());
            }
        }
        catch (IdentityProvisioningException e) {
            throw e;
        }
        catch (Exception e) {
            throw new IdentityProvisioningException("Could not repair merchant portal user in Keycloak", e);
        }
    }

    /** Best-effort compensation when DB onboarding/repair fails after Keycloak creation. */
    public void deleteUserQuietly(String oidcSubject) {
        if (oidcSubject == null || oidcSubject.isBlank()) return;
        try {
            String token = serviceAccountToken();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(userUri(oidcSubject))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token)
                    .DELETE()
                    .build();
            http.send(request, HttpResponse.BodyHandlers.discarding());
        }
        catch (Exception ignored) {
            // Compensation is deliberately best-effort. The original exception
            // remains authoritative and an administrator can remove an orphaned
            // identity through the IdP when needed.
        }
    }

    /**
     * Executes the service account token operation.
     *
     * @return the resulting service account token text
     */
    private String serviceAccountToken() {
        String body = "grant_type=client_credentials"
                + "&client_id=" + form(clientId)
                + "&client_secret=" + form(clientSecret);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(adminBaseUrl + "/realms/" + path(realm) + "/protocol/openid-connect/token"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IdentityProvisioningException("Keycloak service-account token request failed with HTTP " + response.statusCode());
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> json = mapper.readValue(response.body(), Map.class);
            Object token = json.get("access_token");
            if (!(token instanceof String value) || value.isBlank()) {
                throw new IdentityProvisioningException("Keycloak service-account token response did not contain access_token");
            }
            return value;
        }
        catch (IdentityProvisioningException e) {
            throw e;
        }
        catch (Exception e) {
            throw new IdentityProvisioningException("Could not obtain Keycloak service-account token", e);
        }
    }

    /**
     * Executes the users URI operation.
     *
     * @return the resulting {@code URI} value
     */
    private URI usersUri() {
        return URI.create(adminBaseUrl + "/admin/realms/" + path(realm) + "/users");
    }

    /**
     * Executes the user URI operation.
     *
     * @param oidcSubject the OIDC subject value
     * @return the resulting {@code URI} value
     */
    private URI userUri(String oidcSubject) {
        return URI.create(usersUri().toString() + "/" + path(oidcSubject));
    }

    /**
     * Executes the password credential operation.
     *
     * @param password the password value
     * @param temporary whether temporary is enabled
     * @return the resulting key-value data
     */
    private static Map<String, Object> passwordCredential(String password, boolean temporary) {
        Map<String, Object> credential = new LinkedHashMap<>();
        credential.put("type", "password");
        credential.put("value", password);
        credential.put("temporary", temporary);
        return credential;
    }

    /**
     * Retrieves and validates configured.
     */
    private void requireConfigured() {
        if (adminBaseUrl.isBlank() || realm.isBlank() || clientId.isBlank() || clientSecret.isBlank()) {
            throw new IdentityProvisioningException(
                    "Merchant portal identity provisioning is not configured. Configure the Keycloak admin base URL, realm, service-account client ID and client secret.");
        }
    }

    /**
     * Executes the strip trailing slash operation.
     *
     * @param value the value to process
     * @return the resulting strip trailing slash text
     */
    private static String stripTrailingSlash(String value) {
        if (value == null) return "";
        String v = value.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    /**
     * Executes the form operation.
     *
     * @param value the value to process
     * @return the resulting form text
     */
    private static String form(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Executes the path operation.
     *
     * @param value the value to process
     * @return the resulting path text
     */
    private static String path(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * Executes the last path segment operation.
     *
     * @param value the value to process
     * @return the resulting last path segment text
     */
    private static String lastPathSegment(String value) {
        int query = value.indexOf('?');
        String clean = query >= 0 ? value.substring(0, query) : value;
        int slash = clean.lastIndexOf('/');
        return slash >= 0 ? clean.substring(slash + 1) : clean;
    }

    /**
     * Signals a identity provisioning failure.
     */
    public static class IdentityProvisioningException extends RuntimeException {
        /**
         * Creates a new {@code IdentityProvisioningException} instance.
         *
         * @param message the message value
         */
        public IdentityProvisioningException(String message) { super(message); }
        /**
         * Creates a new {@code IdentityProvisioningException} instance.
         *
         * @param message the message value
         * @param cause the cause value
         */
        public IdentityProvisioningException(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * Signals a identity conflict failure.
     */
    public static final class IdentityConflictException extends IdentityProvisioningException {
        /**
         * Creates a new {@code IdentityConflictException} instance.
         *
         * @param message the message value
         */
        public IdentityConflictException(String message) { super(message); }
    }

    /**
     * Signals a identity missing failure.
     */
    public static final class IdentityMissingException extends IdentityProvisioningException {
        /**
         * Creates a new {@code IdentityMissingException} instance.
         *
         * @param message the message value
         */
        public IdentityMissingException(String message) { super(message); }
    }
}
