package com.paymentgateway.provider.mpu.http;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Defines operations provided by the mpu HTTP service.
 */
public interface MpuHttpService {
    /**
     * Executes the inquiry operation.
     *
     * @param merchantId the merchant identifier
     * @param invoiceNo the invoice no value
     * @param inquiryDate the inquiry date value
     * @param actionType the action type value
     * @param hashValue the hash value value
     * @return the resulting {@code ResponseEntity<String>} value
     */
    @PostExchange ResponseEntity<String> inquiry(
        @RequestParam("merchantID") String merchantId,@RequestParam("invoiceNo") String invoiceNo,
        @RequestParam("inquiryDate") String inquiryDate,@RequestParam("actionType") String actionType,@RequestParam("hashValue") String hashValue);

    /**
     * Voids payment.
     *
     * @param merchantId the merchant identifier
     * @param invoiceNo the invoice no value
     * @param actionType the action type value
     * @param amount the amount value
     * @param currencyCode the currency code value
     * @param hashValue the hash value value
     * @return the resulting {@code ResponseEntity<String>} value
     */
    @PostExchange ResponseEntity<String> voidPayment(
        @RequestParam("merchantID") String merchantId,@RequestParam("invoiceNo") String invoiceNo,
        @RequestParam("actionType") String actionType,@RequestParam("amount") String amount,
        @RequestParam("currencyCode") String currencyCode,@RequestParam("hashValue") String hashValue);
}
