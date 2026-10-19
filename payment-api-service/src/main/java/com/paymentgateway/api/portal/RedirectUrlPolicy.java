package com.paymentgateway.api.portal;

import java.net.URI;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Strict redirect URI validation to avoid open redirects and ambiguous URI parsing. */
@Component
public class RedirectUrlPolicy {
    private final boolean allowHttp;
    private final boolean allowLocalhost;

    /**
     * Creates a new {@code RedirectUrlPolicy} instance.
     *
     * @param allowHttp whether allow HTTP is enabled
     * @param allowLocalhost whether allow localhost is enabled
     */
    public RedirectUrlPolicy(
            @Value("${payment.redirect.registration.allow-http:false}") boolean allowHttp,
            @Value("${payment.redirect.registration.allow-localhost:false}") boolean allowLocalhost) {
        this.allowHttp = allowHttp;
        this.allowLocalhost = allowLocalhost;
    }

    /**
     * Validates and normalize.
     *
     * @param rawUrl the raw URL value
     * @return the resulting validate and normalize text
     */
    public String validateAndNormalize(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) throw new IllegalArgumentException("redirectUrl is required");
        try {
            URI uri = URI.create(rawUrl.trim()).normalize();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("https".equals(scheme) || (allowHttp && "http".equals(scheme)))) {
                throw new IllegalArgumentException("Redirect URL must use HTTPS");
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) throw new IllegalArgumentException("Redirect URL must contain a host");
            if (uri.getUserInfo() != null) throw new IllegalArgumentException("Redirect URL must not contain userinfo");
            if (uri.getFragment() != null) throw new IllegalArgumentException("Redirect URL must not contain a fragment");
            if (!allowLocalhost && isLocalhost(host)) {
                throw new IllegalArgumentException("Redirect URL must not target localhost");
            }
            return uri.toString();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid redirect URL", e);
        }
    }

    /**
     * Determines whether localhost.
     *
     * @param host the host value
     * @return {@code true} when the condition is satisfied; otherwise {@code false}
     */
    private static boolean isLocalhost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(h) || h.endsWith(".localhost") || "127.0.0.1".equals(h) || "::1".equals(h);
    }
}
