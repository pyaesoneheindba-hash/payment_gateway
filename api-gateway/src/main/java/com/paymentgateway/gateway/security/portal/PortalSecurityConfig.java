package com.paymentgateway.gateway.security.portal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;

/**
 * Portal authentication is delegated to an external OIDC provider.
 *
 * <p>
 * The gateway validates signature, issuer, expiry and audience. Fine-grained
 * portal authorization is then resolved from the local portal_user entitlement
 * record on every request, so disabling a user or changing a role takes effect
 * immediately without waiting for an access token to expire.
 * </p>
 */
@Configuration
@EnableWebFluxSecurity
public class PortalSecurityConfig {
	/**
	 * Executes the portal security web filter chain operation.
	 *
	 * @param http the HTTP value
	 * @return the resulting {@code SecurityWebFilterChain} value
	 */
	@Bean
	@Order(0)
	SecurityWebFilterChain portalSecurityWebFilterChain(ServerHttpSecurity http) {
		/*
		 * Scope the OAuth2 resource-server chain to portal routes only. This is
		 * critical because the gateway also accepts a separate short-lived bearer token
		 * format for public payment APIs; the OIDC decoder must never try to consume
		 * those tokens.
		 */
		return http.securityMatcher(ServerWebExchangeMatchers.pathMatchers("/api/v1/portal/**"))
				.csrf(ServerHttpSecurity.CsrfSpec::disable).httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
				.formLogin(ServerHttpSecurity.FormLoginSpec::disable)
				.authorizeExchange(exchanges -> exchanges.pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
						.anyExchange().authenticated())
				.oauth2ResourceServer(resource -> resource.jwt(Customizer.withDefaults())).build();
	}

	/**
	 * Executes the non portal security web filter chain operation.
	 *
	 * @param http the HTTP value
	 * @return the resulting {@code SecurityWebFilterChain} value
	 */
	@Bean
	@Order(1)
	SecurityWebFilterChain nonPortalSecurityWebFilterChain(ServerHttpSecurity http) {
		/*
		 * Existing merchant-signature and public-payment-token authentication is
		 * implemented by gateway filters. Spring Security is deliberately passive on
		 * all non-portal routes so those independent credential types continue to work.
		 */
		return http.csrf(ServerHttpSecurity.CsrfSpec::disable).httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
				.formLogin(ServerHttpSecurity.FormLoginSpec::disable)
				.authorizeExchange(exchanges -> exchanges.anyExchange().permitAll()).build();
	}

	/**
	 * Executes the portal JWT decoder operation.
	 *
	 * @param properties the properties value
	 * @return the resulting {@code ReactiveJwtDecoder} value
	 */
	@Bean
	ReactiveJwtDecoder portalJwtDecoder(PortalOidcProperties properties) {
		NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(properties.getJwkSetUri()).build();
		OAuth2TokenValidator<Jwt> issuer = JwtValidators.createDefaultWithIssuer(properties.getIssuer());
		OAuth2TokenValidator<Jwt> audience = jwt -> {
			if (jwt.getAudience() != null && jwt.getAudience().contains(properties.getAudience())) {
				return OAuth2TokenValidatorResult.success();
			}
			return OAuth2TokenValidatorResult
					.failure(new OAuth2Error("invalid_token", "Required portal audience is missing", null));
		};
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuer, audience));
		return decoder;
	}
}
