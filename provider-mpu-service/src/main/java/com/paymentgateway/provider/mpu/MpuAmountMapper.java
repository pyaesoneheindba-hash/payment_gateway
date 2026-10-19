package com.paymentgateway.provider.mpu;

/**
 * Maps mpu amount values between internal and external representations.
 */
public final class MpuAmountMapper {
	/**
	 * Creates a new {@code MpuAmountMapper} instance.
	 */
	private MpuAmountMapper() {
	}

	/**
	 * Converts the current value to twelve digits.
	 *
	 * @param amountMinor the payment amount in minor currency units
	 * @param currency    the ISO currency code
	 * @return the resulting to twelve digits text
	 */
	public static String toTwelveDigits(long amountMinor, String currency) {
		if (!"MMK".equalsIgnoreCase(currency))
			throw new IllegalArgumentException("MPU starter supports MMK only");
		if (amountMinor <= 0 || amountMinor > 999_999_999_999L)
			throw new IllegalArgumentException("MPU amount out of 12-digit range");
		return "%012d".formatted(amountMinor);
	}
}
