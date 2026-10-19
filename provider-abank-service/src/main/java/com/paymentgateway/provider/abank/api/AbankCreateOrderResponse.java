package com.paymentgateway.provider.abank.api;
/**
 * Carries response data for abank create order operations.
 */
public record AbankCreateOrderResponse(Data data,String respondMessage,Integer respondCode){ /**
 * Immutable data carrier for data.
 */
public record Data(String qr){} }
