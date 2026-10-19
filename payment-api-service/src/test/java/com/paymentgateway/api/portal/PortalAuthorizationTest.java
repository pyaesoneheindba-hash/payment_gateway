package com.paymentgateway.api.portal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * Verifies the behavior of portal authorization.
 */
class PortalAuthorizationTest {
    private final PortalAuthorization authorization = new PortalAuthorization();

    /**
     * Executes the merchant viewer is read only operation.
     */
    @Test
    void merchantViewerIsReadOnly() {
        assertDoesNotThrow(() -> authorization.requireMerchantRead("MERCHANT_VIEWER"));
        assertThrows(ResponseStatusException.class,
                () -> authorization.requireMerchantKeyAdmin("MERCHANT_VIEWER"));
        assertThrows(ResponseStatusException.class,
                () -> authorization.requireMerchantWebhookAdmin("MERCHANT_VIEWER"));
        assertThrows(ResponseStatusException.class,
                () -> authorization.requireMerchantReconciliation("MERCHANT_VIEWER"));
    }

    /**
     * Executes the security auditor cannot perform super admin mutation operation.
     */
    @Test
    void securityAuditorCannotPerformSuperAdminMutation() {
        assertDoesNotThrow(() -> authorization.requireAdminRead("SECURITY_AUDITOR"));
        assertThrows(ResponseStatusException.class,
                () -> authorization.requireSuperAdmin("SECURITY_AUDITOR"));
    }

    /**
     * Executes the operations may reconcile but not create merchant operation.
     */
    @Test
    void operationsMayReconcileButNotCreateMerchant() {
        assertDoesNotThrow(() -> authorization.requireAdminReconciliation("OPERATIONS"));
        assertThrows(ResponseStatusException.class,
                () -> authorization.requireSuperAdmin("OPERATIONS"));
    }
}
