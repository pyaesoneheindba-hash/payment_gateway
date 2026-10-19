package com.paymentgateway.provider.ayapay.http;

import com.paymentgateway.provider.ayapay.api.AyaLoginRequest;
import com.paymentgateway.provider.ayapay.api.AyaQrPaymentRequest;
import com.paymentgateway.provider.ayapay.api.AyaStatusRequest;
import com.paymentgateway.provider.ayapay.api.AyaRefundRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.PostExchange;

/** Spring declarative HTTP Service interface for the AYA PAY UAT/production API. */
public interface AyaPayHttpService {
    /**
     * Executes the generate token operation.
     *
     * @param authorization the authorization value
     * @param grantType the grant type value
     * @return the resulting {@code ResponseEntity<String>} value
     */
    @PostExchange(url="/token", contentType=MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<String> generateToken(@RequestHeader("Authorization") String authorization,
                                         @RequestParam("grant_type") String grantType);

    /**
     * Executes the merchant login operation.
     *
     * @param accessToken the access token value
     * @param request the current request or request payload
     * @return the resulting {@code ResponseEntity<String>} value
     */
    @PostExchange(url="/merchant/1.0.0/thirdparty/merchant/login", contentType=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> merchantLogin(@RequestHeader("Token") String accessToken,
                                         @RequestBody AyaLoginRequest request);

    /**
     * Executes the request QR operation.
     *
     * @param sessionToken the session token value
     * @param accessToken the access token value
     * @param request the current request or request payload
     * @return the resulting {@code ResponseEntity<String>} value
     */
    @PostExchange(url="/merchant/1.0.0/thirdparty/merchant/v2/requestQRPayment", contentType=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> requestQr(@RequestHeader("Authorization") String sessionToken,
                                     @RequestHeader("Token") String accessToken,
                                     @RequestBody AyaQrPaymentRequest request);

    /**
     * Executes the request push operation.
     *
     * @param sessionToken the session token value
     * @param accessToken the access token value
     * @param amount the amount value
     * @param currency the ISO currency code
     * @param externalTransactionId the external transaction identifier value
     * @param externalAdditionalData the external additional data value
     * @param customerPhone the customer phone value
     * @param serviceCode the service code value
     * @param timelimit the timelimit value
     * @return the resulting {@code ResponseEntity<String>} value
     */
    @PostExchange(url="/merchant/1.0.0/thirdparty/merchant/v2/requestPushPayment", contentType=MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<String> requestPush(@RequestHeader("Authorization") String sessionToken,
                                       @RequestHeader("Token") String accessToken,
                                       @RequestParam("amount") long amount,
                                       @RequestParam("currency") String currency,
                                       @RequestParam("externalTransactionId") String externalTransactionId,
                                       @RequestParam("externalAdditionalData") String externalAdditionalData,
                                       @RequestParam("customerPhone") String customerPhone,
                                       @RequestParam("serviceCode") String serviceCode,
                                       @RequestParam("timelimit") int timelimit);

    /**
     * Checks QR.
     *
     * @param sessionToken the session token value
     * @param accessToken the access token value
     * @param request the current request or request payload
     * @return the resulting {@code ResponseEntity<String>} value
     */
    @PostExchange(url="/merchant/1.0.0/thirdparty/merchant/checkQRPayment", contentType=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> checkQr(@RequestHeader("Authorization") String sessionToken,
                                   @RequestHeader("Token") String accessToken,
                                   @RequestBody AyaStatusRequest request);

    /**
     * Checks push.
     *
     * @param sessionToken the session token value
     * @param accessToken the access token value
     * @param request the current request or request payload
     * @return the resulting {@code ResponseEntity<String>} value
     */
    @PostExchange(url="/merchant/1.0.0/thirdparty/merchant/checkRequestPayment", contentType=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> checkPush(@RequestHeader("Authorization") String sessionToken,
                                     @RequestHeader("Token") String accessToken,
                                     @RequestBody AyaStatusRequest request);

    /**
     * Checks QR by merchant.
     *
     * @param sessionToken the session token value
     * @param accessToken the access token value
     * @param request the current request or request payload
     * @return the resulting {@code ResponseEntity<String>} value
     */
    @PostExchange(url="/merchant/1.0.0/apiMerchant/qrcode/checkQrByMerchant", contentType=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> checkQrByMerchant(@RequestHeader("Authorization") String sessionToken,
                                              @RequestHeader("Token") String accessToken,
                                              @RequestBody java.util.Map<String,String> request);

    /**
     * Executes the refund operation.
     *
     * @param sessionToken the session token value
     * @param accessToken the access token value
     * @param request the current request or request payload
     * @return the resulting {@code ResponseEntity<String>} value
     */
    @PostExchange(url="/merchant/1.0.0/thirdparty/merchant/refundPayment", contentType=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> refund(@RequestHeader("Authorization") String sessionToken,
                                  @RequestHeader("Token") String accessToken,
                                  @RequestBody AyaRefundRequest request);
}
