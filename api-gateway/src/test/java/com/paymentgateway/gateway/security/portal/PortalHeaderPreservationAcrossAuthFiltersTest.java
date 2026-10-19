package com.paymentgateway.gateway.security.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.paymentgateway.gateway.security.ClientRequestSecurityProperties;
import com.paymentgateway.gateway.security.ClientRequestSignatureFilter;
import com.paymentgateway.gateway.security.ClientSecurityAuditRepository;
import com.paymentgateway.gateway.security.GatewaySecurityHeaders;
import com.paymentgateway.gateway.security.MerchantSigningKeyRepository;
import com.paymentgateway.gateway.security.ReplayCacheService;
import com.paymentgateway.gateway.security.publictoken.PublicClientTokenFilter;
import com.paymentgateway.gateway.security.publictoken.PublicPaymentTokenService;
import com.paymentgateway.gateway.security.publictoken.PublicTokenReplayCacheService;
import com.paymentgateway.gateway.security.publictoken.PublicTokenSecurityAuditRepository;
import com.paymentgateway.gateway.security.publictoken.PublicTokenSecurityProperties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Regression coverage for portal identity headers created by PortalTokenFilter. */
class PortalHeaderPreservationAcrossAuthFiltersTest {

    /**
     * Executes the public token filter does not delete verified portal identity headers operation.
     */
    @Test
    void publicTokenFilterDoesNotDeleteVerifiedPortalIdentityHeaders() {
        PublicClientTokenFilter filter = new PublicClientTokenFilter(
                mock(PublicPaymentTokenService.class),
                mock(PublicTokenReplayCacheService.class),
                mock(PublicTokenSecurityAuditRepository.class),
                mock(PublicTokenSecurityProperties.class));

        ServerWebExchange exchange = portalExchange();
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        GatewayFilterChain chain = e -> { forwarded.set(e); return Mono.empty(); };

        filter.filter(exchange, chain).block();

        assertPortalHeaders(forwarded.get());
    }

    /**
     * Executes the merchant signature filter does not delete verified portal identity headers operation.
     */
    @Test
    void merchantSignatureFilterDoesNotDeleteVerifiedPortalIdentityHeaders() {
        ClientRequestSignatureFilter filter = new ClientRequestSignatureFilter(
                mock(ClientRequestSecurityProperties.class),
                mock(MerchantSigningKeyRepository.class),
                mock(ReplayCacheService.class),
                mock(ClientSecurityAuditRepository.class));

        ServerWebExchange exchange = portalExchange();
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        GatewayFilterChain chain = e -> { forwarded.set(e); return Mono.empty(); };

        filter.filter(exchange, chain).block();

        assertPortalHeaders(forwarded.get());
    }

    /**
     * Executes the portal exchange operation.
     *
     * @return the resulting {@code ServerWebExchange} value
     */
    private static ServerWebExchange portalExchange() {
        UUID userId = UUID.randomUUID();
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/portal/me")
                .header(GatewaySecurityHeaders.AUTH_MODE, GatewaySecurityHeaders.AUTH_MODE_PORTAL)
                .header(GatewaySecurityHeaders.PORTAL_ROLE, "SUPER_ADMIN")
                .header(GatewaySecurityHeaders.PORTAL_USER_ID, userId.toString())
                .build());
    }

    /**
     * Executes the assert portal headers operation.
     *
     * @param exchange the exchange value
     */
    private static void assertPortalHeaders(ServerWebExchange exchange) {
        assertEquals(GatewaySecurityHeaders.AUTH_MODE_PORTAL,
                exchange.getRequest().getHeaders().getFirst(GatewaySecurityHeaders.AUTH_MODE));
        assertEquals("SUPER_ADMIN",
                exchange.getRequest().getHeaders().getFirst(GatewaySecurityHeaders.PORTAL_ROLE));
        String userId = exchange.getRequest().getHeaders().getFirst(GatewaySecurityHeaders.PORTAL_USER_ID);
        // UUID parsing also catches accidental header truncation/corruption.
        UUID.fromString(userId);
    }
}
