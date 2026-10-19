package com.paymentgateway.api.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
/**
 * Carries input data for customer QR pay requests.
 */
public record CustomerQrPayRequest(@NotBlank @Size(max=128) String merchantOrderId,@Min(1) long amountMinor,@NotBlank @Pattern(regexp="(?i)MMK") String currency,@NotBlank @Size(max=200) String description,@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{2,64}") String providerCode,@NotBlank @Size(max=32) String customerPhone,Map<String,String> providerOptions) {}
