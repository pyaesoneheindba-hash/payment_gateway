package com.paymentgateway.provider.mpu;

import com.paymentgateway.common.ProviderOutcome;

/**
 * Maps mpu status values between internal and external representations.
 */
public final class MpuStatusMapper {
	/**
	 * Creates a new {@code MpuStatusMapper} instance.
	 */
	private MpuStatusMapper() {
	}

	/**
	 * Executes the map operation.
	 *
	 * @param responseCode the response code value
	 * @param status       the payment or delivery status
	 * @return the resulting {@code ProviderOutcome} value
	 */
	public static ProviderOutcome map(String responseCode, String status) {
		if ("04".equals(responseCode))
			return ProviderOutcome.NOT_FOUND;
		if (status == null || status.isBlank())
			return "00".equals(responseCode) ? ProviderOutcome.UNKNOWN : ProviderOutcome.REJECTED;
		return switch (status.toUpperCase()) {
		case "AP", "RS", "SE" -> ProviderOutcome.SUCCESS;
		case "PR", "RE", "RR" -> ProviderOutcome.PENDING;
		case "RF" -> ProviderOutcome.REFUNDED;
		case "VO" -> ProviderOutcome.VOIDED;
		case "DE", "FA" -> ProviderOutcome.REJECTED;
		default -> ProviderOutcome.UNKNOWN;
		};
	}
}
