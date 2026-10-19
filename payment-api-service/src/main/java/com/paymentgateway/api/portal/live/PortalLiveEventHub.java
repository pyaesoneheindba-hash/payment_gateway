package com.paymentgateway.api.portal.live;

import com.paymentgateway.common.live.PortalLiveEvent;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * In-memory registry of authenticated browser SSE connections for this payment-api instance.
 * Redis Pub/Sub fans events to every instance; this hub performs final server-side tenant filtering.
 */
@Component
public class PortalLiveEventHub {
    private static final Logger log = LoggerFactory.getLogger(PortalLiveEventHub.class);

    private final Map<UUID, Client> clients = new ConcurrentHashMap<>();
    private final long timeoutMs;

    /**
     * Creates a new {@code PortalLiveEventHub} instance.
     *
     * @param timeoutMs the operation timeout in milliseconds
     */
    public PortalLiveEventHub(@Value("${payment.portal-live.sse-timeout-ms:1800000}") long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    /**
     * Executes the subscribe operation.
     *
     * @param role the authenticated portal role
     * @param merchantId the merchant identifier
     * @return the resulting {@code SseEmitter} value
     */
    public SseEmitter subscribe(String role, UUID merchantId) {
        UUID connectionId = UUID.randomUUID();
        SseEmitter emitter = new SseEmitter(timeoutMs);
        Client client = new Client(connectionId, role, merchantId, emitter);
        clients.put(connectionId, client);
        emitter.onCompletion(() -> clients.remove(connectionId));
        emitter.onTimeout(() -> clients.remove(connectionId));
        emitter.onError(error -> clients.remove(connectionId));
        try {
            emitter.send(SseEmitter.event()
                    .id(connectionId.toString())
                    .name("connected")
                    .data(Map.of("connectedAt", Instant.now().toString()))
                    .reconnectTime(2000));
        }
        catch (IOException e) {
            clients.remove(connectionId);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /**
     * Executes the publish operation.
     *
     * @param event the event to process
     */
    public void publish(PortalLiveEvent event) {
        if (event == null || event.merchantId() == null) return;
        clients.values().forEach(client -> {
            if (!isVisible(client, event)) return;
            try {
                client.emitter().send(SseEmitter.event()
                        .id(event.eventId() == null ? UUID.randomUUID().toString() : event.eventId().toString())
                        .name("payment-status")
                        .data(event)
                        .reconnectTime(2000));
            }
            catch (Exception e) {
                clients.remove(client.connectionId());
                try { client.emitter().complete(); } catch (Exception ignored) { }
            }
        });
    }

    /**
     * Executes the heartbeat operation.
     */
    @Scheduled(fixedDelayString = "${payment.portal-live.heartbeat-ms:15000}")
    public void heartbeat() {
        clients.values().forEach(client -> {
            try {
                client.emitter().send(SseEmitter.event().comment("heartbeat " + Instant.now()));
            }
            catch (Exception e) {
                clients.remove(client.connectionId());
                try { client.emitter().complete(); } catch (Exception ignored) { }
            }
        });
    }

    /**
     * Determines whether visible.
     *
     * @param client the client value
     * @param event the event to process
     * @return {@code true} when the condition is satisfied; otherwise {@code false}
     */
    private boolean isVisible(Client client, PortalLiveEvent event) {
        String role = client.role() == null ? "" : client.role();
        if (role.startsWith("MERCHANT_")) {
            return client.merchantId() != null && client.merchantId().equals(event.merchantId());
        }
        // PortalTokenFilter has already mapped this connection to an authorized admin portal user.
        return role.equals("SUPER_ADMIN") || role.equals("OPERATIONS") || role.equals("FINANCE")
                || role.equals("SUPPORT") || role.equals("SECURITY_AUDITOR");
    }

    /**
     * Executes the connection count operation.
     *
     * @return the resulting connection count value
     */
    public int connectionCount() { return clients.size(); }

    /**
     * Provides client-side access to .
     */
    private record Client(UUID connectionId, String role, UUID merchantId, SseEmitter emitter) { }
}
