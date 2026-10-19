package com.paymentgateway.provider.abank.api;
/**
 * Carries response data for abank error operations.
 */
public record AbankErrorResponse(String errorCode,String errorMessage,String status) {}
