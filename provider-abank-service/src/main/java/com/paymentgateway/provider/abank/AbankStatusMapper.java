package com.paymentgateway.provider.abank;

import com.paymentgateway.common.ProviderOutcome;

/**
 * Maps abank status values between internal and external representations.
 */
public final class AbankStatusMapper {
	/**
	 * Creates a new {@code AbankStatusMapper} instance.
	 */
	private AbankStatusMapper() {
	}

	/**
	 * Executes the map operation.
	 *
	 * @param status the payment or delivery status
	 * @return the resulting {@code ProviderOutcome} value
	 */
	public static ProviderOutcome map(Integer status) {
		if (status == null)
			return ProviderOutcome.UNKNOWN;
		return switch (status) {
		case 200 -> ProviderOutcome.SUCCESS;
		case 100 -> ProviderOutcome.PENDING;
		case 400 -> ProviderOutcome.REFUNDED;
		case 500 -> ProviderOutcome.REJECTED;
		case 403 -> ProviderOutcome.NOT_FOUND;
		default -> ProviderOutcome.UNKNOWN;
		};
	}
}
