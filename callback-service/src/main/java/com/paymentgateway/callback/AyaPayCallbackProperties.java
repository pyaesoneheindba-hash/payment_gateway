package com.paymentgateway.callback;
import org.springframework.boot.context.properties.ConfigurationProperties;
/**
 * Binds configuration properties for aya pay callback.
 */
@ConfigurationProperties(prefix="provider.ayapay")
public record AyaPayCallbackProperties(String decryptionKey) {}
