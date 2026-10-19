package com.paymentgateway.gateway.security.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.paymentgateway.gateway.security.GatewaySecurityHeaders;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Verifies the behavior of portal token filter.
 */
class PortalTokenFilterTest {
    /**
     * Executes the active OIDC user is reloaded and raw bearer is not forwarded operation.
     */
    @Test
    void activeOidcUserIsReloadedAndRawBearerIsNotForwarded() {
        PortalUserRepository users = mock(PortalUserRepository.class);
        UUID userId = UUID.randomUUID();
        when(users.findByOidcSubject("oidc-user-1")).thenReturn(Mono.just(
                new PortalUserRecord(userId, "ops", "Operations", "OPERATIONS", null, "ACTIVE", null)));
        PortalTokenFilter filter = new PortalTokenFilter(users);
        JwtAuthenticationToken authentication = authenticatedJwt("oidc-user-1");
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/portal/admin/summary")
                .header(HttpHeaders.AUTHORIZATION, "Bearer raw-oidc-token")
                .header(GatewaySecurityHeaders.PORTAL_ROLE, "SUPER_ADMIN")
                .header("X-Merchant-Id", UUID.randomUUID().toString())
                .build());
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        GatewayFilterChain chain = e -> { forwarded.set(e); return Mono.empty(); };

        filter.filter(exchange, chain)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))
                .block();

        ServerWebExchange result = forwarded.get();
        assertNotNull(result);
        assertNotEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode(),
                "successful portal authorization must not become PORTAL_USER_NOT_REGISTERED");
        assertNotEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode(),
                "successful portal authorization must not become PORTAL_AUTH_REQUIRED");
        assertSame(exchange.getResponse(), result.getResponse());
        assertNull(result.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        assertNull(result.getRequest().getHeaders().getFirst("X-Merchant-Id"));
        assertEquals("OPERATIONS", result.getRequest().getHeaders().getFirst(GatewaySecurityHeaders.PORTAL_ROLE));
        assertEquals(userId.toString(), result.getRequest().getHeaders().getFirst(GatewaySecurityHeaders.PORTAL_USER_ID));
        assertEquals("PORTAL_OIDC", result.getRequest().getHeaders().getFirst(GatewaySecurityHeaders.AUTH_MODE));
    }


    /**
     * Executes the registered admin SSE request passes through without false empty rejection operation.
     */
    @Test
    void registeredAdminSseRequestPassesThroughWithoutFalseEmptyRejection() {
        PortalUserRepository users = mock(PortalUserRepository.class);
        UUID userId = UUID.randomUUID();
        when(users.findByOidcSubject("admin-sse-subject")).thenReturn(Mono.just(
                new PortalUserRecord(userId, "pgadmin", "Payment Admin", "SUPER_ADMIN", null, "ACTIVE", null)));
        PortalTokenFilter filter = new PortalTokenFilter(users);
        JwtAuthenticationToken authentication = authenticatedJwt("admin-sse-subject");
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/portal/live/events").build());
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        GatewayFilterChain chain = e -> { forwarded.set(e); return Mono.empty(); };

        filter.filter(exchange, chain)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))
                .block();

        ServerWebExchange result = forwarded.get();
        assertNotNull(result, "registered portal user must reach the SSE route");
        assertNotEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode(),
                "successful SSE authorization must not become PORTAL_USER_NOT_REGISTERED");
        assertNotEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode(),
                "successful SSE authorization must not become PORTAL_AUTH_REQUIRED");
        assertEquals("SUPER_ADMIN",
                result.getRequest().getHeaders().getFirst(GatewaySecurityHeaders.PORTAL_ROLE));
        assertEquals(GatewaySecurityHeaders.AUTH_MODE_PORTAL,
                result.getRequest().getHeaders().getFirst(GatewaySecurityHeaders.AUTH_MODE));
    }

    /**
     * Executes the unknown OIDC subject is rejected as not registered operation.
     */
    @Test
    void unknownOidcSubjectIsRejectedAsNotRegistered() {
        PortalUserRepository users = mock(PortalUserRepository.class);
        when(users.findByOidcSubject("unknown-subject")).thenReturn(Mono.empty());
        PortalTokenFilter filter = new PortalTokenFilter(users);
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/portal/live/events").build());

        filter.filter(exchange, e -> Mono.error(new AssertionError("unregistered user must not reach SSE route")))
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authenticatedJwt("unknown-subject")))
                .block();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        assertEquals("{\"error\":\"PORTAL_USER_NOT_REGISTERED\"}",
                exchange.getResponse().getBodyAsString().block());
    }

    /**
     * Executes the missing portal authentication is rejected as auth required operation.
     */
    @Test
    void missingPortalAuthenticationIsRejectedAsAuthRequired() {
        PortalUserRepository users = mock(PortalUserRepository.class);
        PortalTokenFilter filter = new PortalTokenFilter(users);
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/portal/live/events").build());

        filter.filter(exchange, e -> Mono.error(new AssertionError("unauthenticated request must not reach SSE route")))
                .block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertEquals("{\"error\":\"PORTAL_AUTH_REQUIRED\"}",
                exchange.getResponse().getBodyAsString().block());
    }

    /**
     * Executes the disabled local entitlement rejects still valid external token operation.
     */
    @Test
    void disabledLocalEntitlementRejectsStillValidExternalToken() {
        PortalUserRepository users = mock(PortalUserRepository.class);
        when(users.findByOidcSubject("disabled-user")).thenReturn(Mono.just(
                new PortalUserRecord(UUID.randomUUID(), "old-admin", "Old Admin", "SUPER_ADMIN", null, "DISABLED", null)));
        PortalTokenFilter filter = new PortalTokenFilter(users);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/portal/admin/summary").build());

        filter.filter(exchange, e -> Mono.error(new AssertionError("request must not reach upstream")))
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                        authenticatedJwt("disabled-user")))
                .block();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }


    /**
     * Executes the pending merchant admin may bootstrap public key but not use payment portal operation.
     */
    @Test
    void pendingMerchantAdminMayBootstrapPublicKeyButNotUsePaymentPortal() {
        PortalUserRepository users = mock(PortalUserRepository.class);
        UUID merchantId = UUID.randomUUID();
        UUID portalUserId = UUID.randomUUID();
        when(users.findByOidcSubject("pending-bootstrap-admin")).thenReturn(Mono.just(
                new PortalUserRecord(portalUserId, "merchant.admin", "Merchant Admin",
                        "MERCHANT_ADMIN", merchantId, "ACTIVE", "PENDING")));
        PortalTokenFilter filter = new PortalTokenFilter(users);
        JwtAuthenticationToken authentication = authenticatedJwt("pending-bootstrap-admin");

        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/portal/merchant/api-keys").build());
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        GatewayFilterChain chain = e -> { forwarded.set(e); return Mono.empty(); };

        filter.filter(exchange, chain)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))
                .block();

        ServerWebExchange result = forwarded.get();
        assertEquals(merchantId.toString(), result.getRequest().getHeaders().getFirst("X-Merchant-Id"));
        assertEquals("PENDING", result.getRequest().getHeaders().getFirst(GatewaySecurityHeaders.PORTAL_MERCHANT_STATUS));
        assertEquals("MERCHANT_ADMIN", result.getRequest().getHeaders().getFirst(GatewaySecurityHeaders.PORTAL_ROLE));
    }

    /**
     * Executes the merchant portal rejects when merchant is not active operation.
     */
    @Test
    void merchantPortalRejectsWhenMerchantIsNotActive() {
        PortalUserRepository users = mock(PortalUserRepository.class);
        UUID merchantId = UUID.randomUUID();
        when(users.findByOidcSubject("pending-merchant-admin")).thenReturn(Mono.just(
                new PortalUserRecord(UUID.randomUUID(), "merchant.admin", "Merchant Admin",
                        "MERCHANT_ADMIN", merchantId, "ACTIVE", "PENDING")));
        PortalTokenFilter filter = new PortalTokenFilter(users);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/portal/merchant/summary").build());

        filter.filter(exchange, e -> Mono.error(new AssertionError("request must not reach upstream")))
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                        authenticatedJwt("pending-merchant-admin")))
                .block();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    /**
     * Executes the authenticated JWT operation.
     *
     * @param subject the subject value
     * @return the resulting {@code JwtAuthenticationToken} value
     */
    private static JwtAuthenticationToken authenticatedJwt(String subject) {
        // Spring Security 7 treats new JwtAuthenticationToken(jwt) as an
        // unauthenticated authentication request. The resource-server authentication
        // provider returns the authenticated form with authorities. Unit tests must
        // model that post-authentication state.
        return new JwtAuthenticationToken(
                jwt(subject),
                AuthorityUtils.createAuthorityList("SCOPE_portal"));
    }

    /**
     * Executes the JWT operation.
     *
     * @param subject the subject value
     * @return the resulting {@code Jwt} value
     */
    private static Jwt jwt(String subject) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject(subject)
                .issuedAt(now.minusSeconds(1))
                .expiresAt(now.plusSeconds(300))
                .audience(java.util.List.of("payment-portal-api"))
                .build();
    }
}
