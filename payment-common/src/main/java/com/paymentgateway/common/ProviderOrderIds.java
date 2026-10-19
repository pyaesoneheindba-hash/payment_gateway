package com.paymentgateway.common;

import java.util.UUID;

/**
 * Provider-specific external identifiers kept deterministic for
 * retries/recovery.
 */
public final class ProviderOrderIds {
	/**
	 * Creates a new {@code ProviderOrderIds} instance.
	 */
	private ProviderOrderIds() {
	}

	/** ABank guide: orderId maximum length is 20 characters. */
	public static String abank(UUID paymentId, String merchantOrderId) {
		if (merchantOrderId != null && !merchantOrderId.isBlank() && merchantOrderId.length() <= 20) {
			return merchantOrderId;
		}
		return "P" + paymentId.toString().replace("-", "").substring(0, 19);
	}

	/** MPU guide: invoiceNo is 20 characters and shorter values are left padded. */
	public static String mpu(UUID paymentId, String merchantOrderId) {
		String raw = merchantOrderId == null ? "" : merchantOrderId.trim();
		if (!raw.isEmpty() && raw.length() <= 20)
			return "0".repeat(20 - raw.length()) + raw;
		return paymentId.toString().replace("-", "").substring(0, 20).toUpperCase();
	}

	/** AYA PAY guide: externalTransactionId maximum length is 35 characters. */
	public static String ayapay(UUID paymentId, String merchantOrderId) {
		String raw = merchantOrderId == null ? "" : merchantOrderId.trim();
		if (!raw.isEmpty() && raw.length() <= 35)
			return raw;
		return paymentId.toString().replace("-", "").toUpperCase();
	}

	/**
	 * Executes the for provider operation.
	 *
	 * @param providerCode    the payment provider code
	 * @param paymentId       the payment identifier
	 * @param merchantOrderId the merchant-defined order identifier
	 * @return the resulting for provider text
	 */
	public static String forProvider(String providerCode, UUID paymentId, String merchantOrderId) {
		return switch (ProviderCode.normalize(providerCode)) {
		case ProviderCode.ABANK -> abank(paymentId, merchantOrderId);
		case ProviderCode.MPU -> mpu(paymentId, merchantOrderId);
		case ProviderCode.AYAPAY -> ayapay(paymentId, merchantOrderId);
		default -> paymentId.toString().replace("-", "").toUpperCase();
		};
	}
}
