package com.paymentgateway.gateway.security.publictoken;

import com.paymentgateway.gateway.security.GatewaySecurityHeaders;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes HTTP endpoints for public token issuer operations.
 */
@RestController
@RequestMapping("/internal/security/public-tokens")
public class PublicTokenIssuerController {
	private final PublicPaymentTokenService tokenService;

	/**
	 * Creates a new {@code PublicTokenIssuerController} instance.
	 *
	 * @param tokenService the token service value
	 */
	public PublicTokenIssuerController(PublicPaymentTokenService tokenService) {
		this.tokenService = tokenService;
	}

	/**
	 * Executes the issue operation.
	 *
	 * @param merchantId the merchant identifier
	 * @param authMode   the trusted authentication mode set by the gateway
	 * @param request    the current request or request payload
	 * @return the resulting {@code PublicTokenIssueResponse} value
	 */
	@PostMapping
	public PublicTokenIssueResponse issue(@RequestHeader("X-Merchant-Id") UUID merchantId,
			@RequestHeader(GatewaySecurityHeaders.AUTH_MODE) String authMode,
			@RequestBody PublicTokenIssueRequest request) {
		if (!GatewaySecurityHeaders.AUTH_MODE_MERCHANT_SIGNATURE.equals(authMode)) {
			throw new PublicTokenException("TRUSTED_SIGNATURE_REQUIRED",
					"Public tokens may only be issued after merchant Ed25519 authentication");
		}
		return tokenService.issue(merchantId, request);
	}

	/**
	 * Executes the token error operation.
	 *
	 * @param error the failure that triggered error handling
	 * @return the resulting {@code ResponseEntity<Map<String, String>>} value
	 */
	@ExceptionHandler(PublicTokenException.class)
	public ResponseEntity<Map<String, String>> tokenError(PublicTokenException error) {
		HttpStatus status = error.code().contains("NOT_CONFIGURED") || error.code().contains("SIGNING_FAILED")
				? HttpStatus.SERVICE_UNAVAILABLE
				: HttpStatus.BAD_REQUEST;
		return ResponseEntity.status(status).body(Map.of("error", error.code(), "message", error.getMessage()));
	}
}
