package com.paymentgateway.provider.mpu;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds configuration properties for mpu.
 */
@ConfigurationProperties(prefix = "provider.mpu")
public record MpuProperties(String merchantId, String secretKey, String paymentUrl, String actionUrl,
		String frontendUrl, String backendUrl, String version, String currencyCode, String categoryCode) {
}
