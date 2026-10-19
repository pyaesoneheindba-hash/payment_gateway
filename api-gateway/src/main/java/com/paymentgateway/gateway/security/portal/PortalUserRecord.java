package com.paymentgateway.gateway.security.portal;

import java.util.UUID;

/**
 * Immutable data carrier for portal user record.
 */
public record PortalUserRecord(UUID portalUserId, String username, String displayName,
                               String role, UUID merchantId, String status, String merchantStatus) {}
