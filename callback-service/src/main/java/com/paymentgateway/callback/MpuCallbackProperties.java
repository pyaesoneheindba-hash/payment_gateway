package com.paymentgateway.callback;

import org.springframework.boot.context.properties.ConfigurationProperties;
/**
 * Binds configuration properties for mpu callback.
 */
@ConfigurationProperties(prefix="provider.mpu")
public record MpuCallbackProperties(String secretKey) {}
