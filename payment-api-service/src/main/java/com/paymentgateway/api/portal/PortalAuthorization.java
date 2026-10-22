package com.paymentgateway.api.portal;

import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Server-side RBAC for portal APIs. Angular guards are UX only. */
@Component
public class PortalAuthorization {
    public static final Set<String> ADMIN_READ = Set.of(
            "SUPER_ADMIN", "OPERATIONS", "FINANCE", "SUPPORT", "SECURITY_AUDITOR");
    public static final Set<String> MERCHANT_READ = Set.of(
            "MERCHANT_ADMIN", "MERCHANT_DEVELOPER", "MERCHANT_FINANCE", "MERCHANT_VIEWER");

    /**
     * Retrieves and validates admin read.
     *
     * @param role the authenticated portal role
     */
    public void requireAdminRead(String role) {
        require(role, ADMIN_READ);
    }

    /**
     * Retrieves and validates merchant read.
     *
     * @param role the authenticated portal role
     */
    public void requireMerchantRead(String role) {
        require(role, MERCHANT_READ);
    }

    /**
     * Retrieves and validates super admin.
     *
     * @param role the authenticated portal role
     */
    public void requireSuperAdmin(String role) {
        require(role, Set.of("SUPER_ADMIN"));
    }

    /**
     * Retrieves and validates admin reconciliation.
     *
     * @param role the authenticated portal role
     */
    public void requireAdminReconciliation(String role) {
        require(role, Set.of("SUPER_ADMIN", "OPERATIONS"));
    }

    /**
     * Retrieves and validates merchant reconciliation.
     *
     * @param role the authenticated portal role
     */
    public void requireMerchantReconciliation(String role) {
        require(role, Set.of("MERCHANT_ADMIN", "MERCHANT_FINANCE"));
    }

    /**
     * Retrieves and validates merchant key admin.
     *
     * @param role the authenticated portal role
     */
    public void requireMerchantKeyAdmin(String role) {
        require(role, Set.of("MERCHANT_ADMIN"));
    }

    /**
     * Retrieves and validates merchant webhook admin.
     *
     * @param role the authenticated portal role
     */
    public void requireMerchantWebhookAdmin(String role) {
        require(role, Set.of("MERCHANT_ADMIN", "MERCHANT_DEVELOPER"));
    }

    /**
     * Executes the require operation.
     *
     * @param role    the authenticated portal role
     * @param allowed the allowed value
     */
    public void require(String role, Set<String> allowed) {
        if (role == null || !allowed.contains(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Portal role is not authorized for this operation");
        }
    }
}
