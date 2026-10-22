package com.paymentgateway.api.checkout;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes HTTP endpoints for hosted checkout operations.
 */
@RestController
@RequestMapping("/api/v1/checkout")
public class HostedCheckoutController {
	private final CheckoutSessionService sessions;

	/**
	 * Creates a new {@code HostedCheckoutController} instance.
	 *
	 * @param sessions the sessions value
	 */
	public HostedCheckoutController(CheckoutSessionService sessions) {
		this.sessions = sessions;
	}

	/**
	 * Executes the get operation.
	 *
	 * @param token the authentication or verification token
	 * @return the resulting {@code ResponseEntity<?>} value
	 */
	@GetMapping("/{token}")
	public ResponseEntity<?> get(@PathVariable String token) {
		if (token == null || !token.matches("[A-Za-z0-9_-]{40,80}"))
			return ResponseEntity.badRequest().body(Map.of("error", "INVALID_CHECKOUT_TOKEN"));
		Map<String, Object> value = sessions.get(token);
		return value == null ? ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "CHECKOUT_NOT_FOUND"))
				: ResponseEntity.ok(value);
	}

	/**
	 * Executes the status operation.
	 *
	 * @param token the authentication or verification token
	 * @return the resulting {@code ResponseEntity<?>} value
	 */
	@GetMapping("/{token}/status")
	public ResponseEntity<?> status(@PathVariable String token) {
		Map<String, Object> value = sessions.get(token);
		if (value == null)
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "CHECKOUT_NOT_FOUND"));
		return ResponseEntity.ok(Map.of("paymentId", value.get("paymentId"), "status", value.get("status"), "expiresAt",
				value.get("expiresAt"), "returnUrl", value.get("returnUrl"), "serverTime", value.get("serverTime")));
	}

	/**
	 * Executes the redirected operation.
	 *
	 * @param token the authentication or verification token
	 * @return the resulting {@code ResponseEntity<?>} value
	 */
	@PostMapping("/{token}/redirected")
	public ResponseEntity<?> redirected(@PathVariable String token) {
		sessions.markRedirected(token);
		return ResponseEntity.noContent().build();
	}
}
