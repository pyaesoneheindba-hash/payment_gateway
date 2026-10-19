package com.paymentgateway.common;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Canonical provider codes. Known aliases are normalized, while future provider
 * codes are accepted without requiring a payment-core change.
 */
public final class ProviderCode {
	public static final String ABANK = "ABANK_APLUS";
	public static final String MPU = "MPU";
	public static final String AYAPAY = "AYAPAY";
	private static final Pattern CODE = Pattern.compile("[A-Z0-9][A-Z0-9_-]{1,63}");

	/**
	 * Creates a new {@code ProviderCode} instance.
	 */
	private ProviderCode() {
	}

	/**
	 * Executes the normalize operation.
	 *
	 * @param value the value to process
	 * @return the resulting normalize text
	 */
	public static String normalize(String value) {
		if (value == null || value.isBlank())
			return ABANK;
		String v = value.trim().toUpperCase(Locale.ROOT);
		v = switch (v) {
		case "ABANK", "APLUS", "ABANK_APLUS" -> ABANK;
		case "MPU" -> MPU;
		case "AYA", "AYA_PAY", "AYAPAY" -> AYAPAY;
		default -> v;
		};
		if (!CODE.matcher(v).matches()) {
			throw new IllegalArgumentException("Invalid providerCode: " + value);
		}
		return v;
	}
}
