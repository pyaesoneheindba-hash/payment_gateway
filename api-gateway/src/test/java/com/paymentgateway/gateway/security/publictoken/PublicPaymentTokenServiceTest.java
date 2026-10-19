package com.paymentgateway.gateway.security.publictoken;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Verifies the behavior of public payment token service.
 */
class PublicPaymentTokenServiceTest {
    /**
     * Executes the issues and validates payment bound token operation.
     */
    @Test
    void issuesAndValidatesPaymentBoundToken() {
        var properties = new PublicTokenSecurityProperties();
        properties.setHmacSecretBase64(Base64.getEncoder().encodeToString(new byte[32]));
        var service = new PublicPaymentTokenService(properties);
        UUID merchantId = UUID.randomUUID();

        var issued = service.issue(merchantId, new PublicTokenIssueRequest(
                "checkout-1", Set.of("payment:create"), 120L, null,
                "ORDER-1", 2000L, "MMK", "ABANK_APLUS", null, true));
        var claims = service.validate(issued.accessToken());

        assertEquals(merchantId, claims.merchantId());
        assertEquals("ORDER-1", claims.merchantOrderId());
        assertEquals(2000L, claims.amountMinor());
        assertEquals("MMK", claims.currency());
        assertEquals("ABANK_APLUS", claims.providerCode());
        assertTrue(claims.oneTime());
        assertTrue(claims.scopes().contains("payment:create"));
    }

    /**
     * Executes the public write token cannot be multi use operation.
     */
    @Test
    void publicWriteTokenCannotBeMultiUse() {
        var properties = new PublicTokenSecurityProperties();
        properties.setHmacSecretBase64(Base64.getEncoder().encodeToString(new byte[32]));
        var service = new PublicPaymentTokenService(properties);

        assertThrows(PublicTokenException.class, () -> service.issue(UUID.randomUUID(), new PublicTokenIssueRequest(
                "checkout-1", Set.of("payment:create"), 120L, null,
                "ORDER-1", 2000L, "MMK", "ABANK_APLUS", null, false)));
    }
}
