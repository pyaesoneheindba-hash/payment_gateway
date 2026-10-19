package com.paymentgateway.notification;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

import org.springframework.stereotype.Component;

/**
 * Implements webhook URL guard behavior for the payment gateway platform.
 */
@Component
public class WebhookUrlGuard {
	private final WebhookNotificationProperties properties;

	/**
	 * Creates a new {@code WebhookUrlGuard} instance.
	 *
	 * @param properties the properties value
	 */
	public WebhookUrlGuard(WebhookNotificationProperties properties) {
		this.properties = properties;
	}

	/**
	 * Executes the validate operation.
	 *
	 * @param rawUrl the raw URL value
	 * @return the resulting {@code URI} value
	 */
	public URI validate(String rawUrl) {
		try {
			URI uri = URI.create(rawUrl);
			String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
			if (!("https".equals(scheme) || (properties.isAllowHttp() && "http".equals(scheme)))) {
				throw new IllegalArgumentException("Webhook URL must use HTTPS");
			}
			if (uri.getHost() == null || uri.getHost().isBlank())
				throw new IllegalArgumentException("Webhook host is missing");
			if (uri.getUserInfo() != null || uri.getFragment() != null)
				throw new IllegalArgumentException("Webhook URL contains disallowed components");
			InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
			if (addresses.length == 0)
				throw new IllegalArgumentException("Webhook host did not resolve");
			if (!properties.isAllowPrivateAddresses()) {
				for (InetAddress address : addresses) {
					if (isPrivateOrSpecial(address)) {
						throw new IllegalArgumentException("Webhook host resolves to private/special address space");
					}
				}
			}
			return uri;
		} catch (IllegalArgumentException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalArgumentException("Webhook URL could not be validated", e);
		}
	}

	/**
	 * Determines whether private or special.
	 *
	 * @param a the a value
	 * @return {@code true} when the condition is satisfied; otherwise {@code false}
	 */
	private static boolean isPrivateOrSpecial(InetAddress a) {
		if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
				|| a.isMulticastAddress())
			return true;
		byte[] raw = a.getAddress();
		if (a instanceof Inet6Address && raw.length == 16) {
			int first = raw[0] & 0xff;
			if ((first & 0xfe) == 0xfc)
				return true; // fc00::/7 unique-local
		}
		if (raw.length == 4) {
			int b0 = raw[0] & 0xff;
			int b1 = raw[1] & 0xff;
			if (b0 == 0 || b0 == 10 || b0 == 127)
				return true;
			if (b0 == 169 && b1 == 254)
				return true;
			if (b0 == 172 && b1 >= 16 && b1 <= 31)
				return true;
			if (b0 == 192 && b1 == 168)
				return true;
			if (b0 == 100 && b1 >= 64 && b1 <= 127)
				return true;
		}
		return false;
	}
}
