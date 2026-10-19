package com.paymentgateway.provider.ayapay;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds configuration properties for aya pay.
 */
@ConfigurationProperties(prefix="provider.ayapay")
public record AyaPayProperties(
        String baseUrl,
        String consumerKey,
        String consumerSecret,
        String merchantPhone,
        String merchantPassword,
        String decryptionKey,
        String qrServiceCode,
        String pushServiceCode,
        Integer qrTimeLimit,
        Integer pushTimeLimit,
        Boolean mmqr
) {}
