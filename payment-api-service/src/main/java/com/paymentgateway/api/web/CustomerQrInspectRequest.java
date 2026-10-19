package com.paymentgateway.api.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
/**
 * Carries input data for customer QR inspect requests.
 */
public record CustomerQrInspectRequest(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{2,64}") String providerCode,@NotBlank @Size(max=10000) String qrData,@NotBlank @Size(max=128) String deviceId,@NotBlank @Size(max=128) String shopId,Map<String,String> providerOptions) {}
