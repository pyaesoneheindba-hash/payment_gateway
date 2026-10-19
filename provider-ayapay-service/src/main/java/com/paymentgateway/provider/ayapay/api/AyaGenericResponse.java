package com.paymentgateway.provider.ayapay.api;

import java.util.Map;

/**
 * Carries response data for aya generic operations.
 */
public record AyaGenericResponse(Integer err,String message,Map<String,Object> data) {}
