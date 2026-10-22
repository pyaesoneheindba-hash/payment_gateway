package com.paymentgateway.api.portal.live;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.paymentgateway.api.portal.PortalAuthorization;

/**
 * Exposes HTTP endpoints for portal live event operations.
 */
@RestController
@RequestMapping("/api/v1/portal/live")
public class PortalLiveEventController {
    private static final String ROLE_HEADER = "X-Payment-Gateway-Portal-Role";
    private final PortalAuthorization authorization;
    private final PortalLiveEventHub hub;

    /**
     * Creates a new {@code PortalLiveEventController} instance.
     *
     * @param authorization the authorization value
     * @param hub           the hub value
     */
    public PortalLiveEventController(PortalAuthorization authorization, PortalLiveEventHub hub) {
        this.authorization = authorization;
        this.hub = hub;
    }

    /**
     * Streams live portal payment events and heartbeat messages to the authenticated client.
     *
     * @param role       the authenticated portal role
     * @param merchantId the merchant identifier
     * @return the resulting {@code ResponseEntity<SseEmitter>} value
     */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(@RequestHeader(ROLE_HEADER) String role,
            @RequestHeader(value = "X-Merchant-Id", required = false) UUID merchantId) {
        if (role != null && role.startsWith("MERCHANT_")) {
            authorization.requireMerchantRead(role);
            if (merchantId == null)
                throw new IllegalArgumentException("Merchant identity is required for live events");
        } else {
            authorization.requireAdminRead(role);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_EVENT_STREAM);
        headers.setCacheControl(CacheControl.noStore());
        headers.set("X-Accel-Buffering", "no");
        headers.set("Connection", "keep-alive");
        return ResponseEntity.ok().headers(headers).body(hub.subscribe(role, merchantId));
    }
}
