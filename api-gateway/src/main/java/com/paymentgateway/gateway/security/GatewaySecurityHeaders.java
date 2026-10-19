package com.paymentgateway.gateway.security;

import org.springframework.http.HttpHeaders;

/**
 * Internal headers created only by api-gateway after authentication succeeds.
 */
public final class GatewaySecurityHeaders {
	/**
	 * Creates a new {@code GatewaySecurityHeaders} instance.
	 */
	private GatewaySecurityHeaders() {
	}

	public static final String AUTH_MODE = "X-Payment-Gateway-Auth-Mode";
	public static final String AUTH_MODE_MERCHANT_SIGNATURE = "MERCHANT_SIGNATURE";
	public static final String AUTH_MODE_PUBLIC_TOKEN = "PUBLIC_TOKEN";
	public static final String VERIFIED_KEY_ID = "X-Payment-Gateway-Client-Key-Id";
	public static final String PUBLIC_TOKEN_SUBJECT = "X-Payment-Gateway-Token-Subject";
	public static final String PUBLIC_TOKEN_JTI = "X-Payment-Gateway-Token-Jti";
	public static final String PUBLIC_TOKEN_PAYMENT_ID = "X-Payment-Gateway-Token-Payment-Id";
	public static final String PUBLIC_TOKEN_MERCHANT_ORDER_ID = "X-Payment-Gateway-Token-Merchant-Order-Id";
	public static final String AUTH_MODE_PORTAL = "PORTAL_OIDC";
	public static final String PORTAL_ROLE = "X-Payment-Gateway-Portal-Role";
	public static final String PORTAL_USER_ID = "X-Payment-Gateway-Portal-User-Id";
	public static final String PORTAL_MERCHANT_STATUS = "X-Payment-Gateway-Merchant-Status";

	/**
	 * Removes internal.
	 *
	 * @param headers the headers value
	 */
	public static void removeInternal(HttpHeaders headers) {
		headers.remove(AUTH_MODE);
		headers.remove(VERIFIED_KEY_ID);
		headers.remove(PUBLIC_TOKEN_SUBJECT);
		headers.remove(PUBLIC_TOKEN_JTI);
		headers.remove(PUBLIC_TOKEN_PAYMENT_ID);
		headers.remove(PUBLIC_TOKEN_MERCHANT_ORDER_ID);
		removePortalInternal(headers);
	}

	/**
	 * Removes portal internal.
	 *
	 * @param headers the headers value
	 */
	public static void removePortalInternal(HttpHeaders headers) {
		headers.remove(PORTAL_ROLE);
		headers.remove(PORTAL_USER_ID);
		headers.remove(PORTAL_MERCHANT_STATUS);
	}
}
