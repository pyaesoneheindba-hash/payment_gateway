package com.paymentgateway.gateway.portal.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.paymentgateway.gateway.security.GatewaySecurityHeaders;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

/**
 * Verifies the behavior of portal live SSE controller.
 */
class PortalLiveSseControllerTest {

    /**
     * Executes the Redis branch failure does not terminate connected SSE heartbeat operation.
     */
    @Test
    void redisBranchFailureDoesNotTerminateConnectedSseHeartbeat() {
        PortalLiveRedisStream stream = mock(PortalLiveRedisStream.class);
        when(stream.events()).thenReturn(Flux.error(
                new IllegalStateException("Unable to connect; Connection is closed")));

        PortalLiveSseController controller = new PortalLiveSseController(
                stream, new ObjectMapper(), 5_000);

        StepVerifier.withVirtualTime(() -> controller.events(
                        GatewaySecurityHeaders.AUTH_MODE_PORTAL,
                        "SUPER_ADMIN",
                        null).take(2))
                .assertNext(event -> assertEquals("connected", event.event()))
                .thenAwait(Duration.ofSeconds(5))
                .expectNextCount(1)
                .verifyComplete();
    }
}
