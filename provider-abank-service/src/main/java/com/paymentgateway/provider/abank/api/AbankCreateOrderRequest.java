package com.paymentgateway.provider.abank.api;

import java.math.BigDecimal;

/**
 * Carries input data for abank create order requests.
 */
public record AbankCreateOrderRequest(
        String requestNo,
        String orderId,
        String merchantId,
        BigDecimal amount,
        String currency,
        int rewardPoint,
        String description,
        String createdDate
) {}
