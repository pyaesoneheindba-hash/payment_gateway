package com.paymentgateway.api.webhook;

import java.net.URI;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Implements webhook URL policy behavior for the payment gateway platform.
 */
@Component
public class WebhookUrlPolicy {
    private final boolean allowHttp;
    private final boolean allowPrivateAddresses;

    /**
     * Creates a new {@code WebhookUrlPolicy} instance.
     *
     * @param allowHttp whether allow HTTP is enabled
     * @param allowPrivateAddresses whether allow private addresses is enabled
     */
    public WebhookUrlPolicy(
            @Value("${payment.webhook.registration.allow-http:false}") boolean allowHttp,
            @Value("${payment.webhook.registration.allow-private-addresses:false}") boolean allowPrivateAddresses) {
        this.allowHttp = allowHttp;
        this.allowPrivateAddresses = allowPrivateAddresses;
    }

    /**
     * Validates and normalize.
     *
     * @param rawUrl the raw URL value
     * @return the resulting validate and normalize text
     */
    public String validateAndNormalize(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("https".equals(scheme) || (allowHttp && "http".equals(scheme)))) {
                throw new IllegalArgumentException("Webhook URL must use HTTPS");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) throw new IllegalArgumentException("Webhook URL must contain a host");
            if (uri.getUserInfo() != null) throw new IllegalArgumentException("Webhook URL must not contain userinfo");
            if (uri.getFragment() != null) throw new IllegalArgumentException("Webhook URL must not contain a fragment");
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (!allowPrivateAddresses && ("localhost".equals(host) || host.endsWith(".localhost") || isPrivateIpv4Literal(host))) {
                throw new IllegalArgumentException("Webhook URL must not target localhost/private address space");
            }
            return uri.normalize().toString();
        }
        catch (IllegalArgumentException e) {
            throw e;
        }
        catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid webhook URL", e);
        }
    }

    /**
     * Determines whether private ipv4 literal.
     *
     * @param host the host value
     * @return {@code true} when the condition is satisfied; otherwise {@code false}
     */
    private static boolean isPrivateIpv4Literal(String host) {
        String[] p = host.split("\\.");
        if (p.length != 4) return false;
        int[] n = new int[4];
        try {
            for (int i = 0; i < 4; i++) n[i] = Integer.parseInt(p[i]);
        }
        catch (NumberFormatException e) { return false; }
        if (n[0] == 10 || n[0] == 127 || n[0] == 0) return true;
        if (n[0] == 169 && n[1] == 254) return true;
        if (n[0] == 172 && n[1] >= 16 && n[1] <= 31) return true;
        if (n[0] == 192 && n[1] == 168) return true;
        return n[0] == 100 && n[1] >= 64 && n[1] <= 127;
    }
}
