package com.paymentgateway.api.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * Carries input data for create payment requests.
 */
public record CreatePaymentRequest(
        @NotBlank @Size(max = 128) String merchantOrderId,
        @Min(1) long amountMinor,
        @NotBlank @Pattern(regexp = "(?i)MMK", message = "currency must be MMK") String currency,
        @NotBlank @Size(max = 200) String description,
        @Pattern(regexp = "[A-Za-z0-9_-]{2,64}", message = "providerCode contains invalid characters") String providerCode,
        Map<String, String> providerOptions,
        @Pattern(regexp = "(?i)QR_STRING|HOSTED_PAGE", message = "presentationMode must be QR_STRING or HOSTED_PAGE") String presentationMode,
        @Size(max = 2000) String returnUrl
) {}
