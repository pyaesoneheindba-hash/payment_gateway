package com.paymentgateway.gateway.security.portal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** External OpenID Connect settings for merchant/admin browser portals. */
@Component
@ConfigurationProperties(prefix = "payment.security.portal-oidc")
public class PortalOidcProperties {
	private String issuer = "http://127.0.0.1:8180/realms/payment-gateway";
	private String jwkSetUri = "http://127.0.0.1:8180/realms/payment-gateway/protocol/openid-connect/certs";
	private String audience = "payment-portal-api";

	/**
	 * Retrieves issuer.
	 *
	 * @return the resulting get issuer text
	 */
	public String getIssuer() {
		return issuer;
	}

	/**
	 * Executes the set issuer operation.
	 *
	 * @param issuer the issuer value
	 */
	public void setIssuer(String issuer) {
		this.issuer = issuer;
	}

	/**
	 * Retrieves jwk set URI.
	 *
	 * @return the resulting get jwk set URI text
	 */
	public String getJwkSetUri() {
		return jwkSetUri;
	}

	/**
	 * Executes the set jwk set URI operation.
	 *
	 * @param jwkSetUri the jwk set URI value
	 */
	public void setJwkSetUri(String jwkSetUri) {
		this.jwkSetUri = jwkSetUri;
	}

	/**
	 * Retrieves audience.
	 *
	 * @return the resulting get audience text
	 */
	public String getAudience() {
		return audience;
	}

	/**
	 * Executes the set audience operation.
	 *
	 * @param audience the audience value
	 */
	public void setAudience(String audience) {
		this.audience = audience;
	}
}
