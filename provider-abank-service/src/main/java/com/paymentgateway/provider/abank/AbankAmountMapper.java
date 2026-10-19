package com.paymentgateway.provider.abank;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Maps abank amount values between internal and external representations.
 */
public final class AbankAmountMapper {
    /**
     * Creates a new {@code AbankAmountMapper} instance.
     */
    private AbankAmountMapper() {}
    /**
     * Creates a value from minor.
     *
     * @param amountMinor the payment amount in minor currency units
     * @param currency the ISO currency code
     * @return the resulting {@code BigDecimal} value
     */
    public static BigDecimal fromMinor(long amountMinor,String currency){
        int exponent="MMK".equalsIgnoreCase(currency)?0:2; // TODO use ISO-4217 metadata for additional currencies.
        return BigDecimal.valueOf(amountMinor).movePointLeft(exponent).setScale(2, RoundingMode.UNNECESSARY);
    }
}
