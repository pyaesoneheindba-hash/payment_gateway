package com.paymentgateway.gateway.portal.live;

import com.paymentgateway.common.live.PortalLiveEvent;
import com.paymentgateway.gateway.security.GatewaySecurityHeaders;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

/**
 * Internal target of the public /api/v1/portal/live/events gateway route.
 *
 * <p>
 * OIDC is validated on the original public route. PortalTokenFilter removes the
 * browser bearer token and creates the trusted portal role / merchant headers
 * before the route is forwarded here. A direct request to this internal path
 * cannot forge those headers because gateway security filters strip browser
 * supplied internal headers.
 * </p>
 */
@RestController
public class PortalLiveSseController {
	private static final Logger log = LoggerFactory.getLogger(PortalLiveSseController.class);
	private static final Set<String> ADMIN_READ = Set.of("SUPER_ADMIN", "OPERATIONS", "FINANCE", "SUPPORT",
			"SECURITY_AUDITOR");
	private static final Set<String> MERCHANT_READ = Set.of("MERCHANT_ADMIN", "MERCHANT_DEVELOPER", "MERCHANT_FINANCE",
			"MERCHANT_VIEWER");

	private final PortalLiveRedisStream stream;
	private final ObjectMapper mapper;
	private final Duration heartbeat;

	/**
	 * Creates a new {@code PortalLiveSseController} instance.
	 *
	 * @param stream      the live payment event stream
	 * @param mapper      the object mapper used to serialize and deserialize JSON
	 * @param heartbeatMs the heartbeat interval in milliseconds
	 */
	public PortalLiveSseController(PortalLiveRedisStream stream, ObjectMapper mapper,
			@Value("${payment.portal-live.heartbeat-ms:15000}") long heartbeatMs) {
		this.stream = stream;
		this.mapper = mapper;
		this.heartbeat = Duration.ofMillis(Math.max(5_000, heartbeatMs));
	}

	/**
	 * Streams live portal payment events and heartbeat messages to the
	 * authenticated client.
	 *
	 * @param authMode   the trusted authentication mode set by the gateway
	 * @param role       the authenticated portal role
	 * @param merchantId the merchant identifier
	 * @return a reactive stream of the requested values
	 */
	@GetMapping(value = "/internal/portal/live/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<String>> events(@RequestHeader(GatewaySecurityHeaders.AUTH_MODE) String authMode,
			@RequestHeader(GatewaySecurityHeaders.PORTAL_ROLE) String role,
			@RequestHeader(value = "X-Merchant-Id", required = false) UUID merchantId) {
		authorize(authMode, role, merchantId);

		ServerSentEvent<String> connected = ServerSentEvent.<String>builder().id(UUID.randomUUID().toString())
				.event("connected").retry(Duration.ofSeconds(2))
				.data("{\"connectedAt\":\"" + Instant.now() + "\",\"transport\":\"api-gateway-redis\"}").build();

		Flux<ServerSentEvent<String>> paymentEvents = stream.events().filter(event -> visible(role, merchantId, event))
				.map(this::toSse).onErrorResume(error -> {
					// Redis recovery is handled inside PortalLiveRedisStream. This is a
					// final safety boundary: an unexpected live-event failure must not
					// tear down an already-committed browser SSE response. Heartbeats
					// remain active and the normal REST safety sync stays authoritative.
					log.error("Portal live event branch failed; keeping SSE heartbeat alive", error);
					return Flux.never();
				});

		Flux<ServerSentEvent<String>> heartbeats = Flux.interval(heartbeat)
				.map(tick -> ServerSentEvent.<String>builder().comment("heartbeat " + Instant.now()).build());

		return Flux.concat(Flux.just(connected), Flux.merge(paymentEvents, heartbeats));
	}

	/**
	 * Validates that the authenticated portal principal is authorized for this
	 * operation.
	 *
	 * @param authMode   the trusted authentication mode set by the gateway
	 * @param role       the authenticated portal role
	 * @param merchantId the merchant identifier
	 */
	private void authorize(String authMode, String role, UUID merchantId) {
		if (!GatewaySecurityHeaders.AUTH_MODE_PORTAL.equals(authMode)) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Portal authentication is required");
		}
		if (role != null && role.startsWith("MERCHANT_")) {
			if (!MERCHANT_READ.contains(role) || merchantId == null) {
				throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Merchant portal role is not authorized");
			}
			return;
		}
		if (!ADMIN_READ.contains(role)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin portal role is not authorized");
		}
	}

	/**
	 * Determines whether the supplied live event is visible to the current portal
	 * principal.
	 *
	 * @param role       the authenticated portal role
	 * @param merchantId the merchant identifier
	 * @param event      the event to process
	 * @return {@code true} when the condition is satisfied; otherwise {@code false}
	 */
	private boolean visible(String role, UUID merchantId, PortalLiveEvent event) {
		if (event == null || event.merchantId() == null)
			return false;
		if (role != null && role.startsWith("MERCHANT_")) {
			return merchantId != null && merchantId.equals(event.merchantId());
		}
		return ADMIN_READ.contains(role);
	}

	/**
	 * Converts the current value to SSE.
	 *
	 * @param event the event to process
	 * @return the resulting {@code ServerSentEvent<String>} value
	 */
	private ServerSentEvent<String> toSse(PortalLiveEvent event) {
		try {
			return ServerSentEvent.<String>builder(mapper.writeValueAsString(event))
					.id(event.eventId() == null ? UUID.randomUUID().toString() : event.eventId().toString())
					.event("payment-status").retry(Duration.ofSeconds(2)).build();
		} catch (Exception e) {
			throw new IllegalStateException("Could not serialize portal live event", e);
		}
	}
}
