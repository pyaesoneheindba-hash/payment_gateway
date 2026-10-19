package com.paymentgateway.provider.abank;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds configuration properties for abank.
 */
@ConfigurationProperties(prefix="provider.abank")
public record AbankProperties(String baseUrl,String secretKey,String eccode,String merchantId) {}
