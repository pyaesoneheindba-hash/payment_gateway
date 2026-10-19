package com.paymentgateway.provider.abank.http;

import com.paymentgateway.provider.abank.api.AbankCreateOrderRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Declarative Spring HTTP Service contract for the ABank/APlus MMQR API.
 *
 * <p>Spring generates the runtime implementation. Application code does not
 * construct or call {@code RestClient} directly.</p>
 */
public interface AbankHttpService {

    /**
     * Creates order.
     *
     * @param secretKey the secret key value
     * @param eccode the eccode value
     * @param request the current request or request payload
     * @return the resulting {@code ResponseEntity<String>} value
     */
    @PostExchange(
            url = "/v1/order/create",
            contentType = MediaType.APPLICATION_JSON_VALUE,
            accept = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> createOrder(
            @RequestHeader("secretKey") String secretKey,
            @RequestHeader("eccode") String eccode,
            @RequestBody AbankCreateOrderRequest request);

    /**
     * Executes the enquire order operation.
     *
     * @param secretKey the secret key value
     * @param eccode the eccode value
     * @param orderId the provider or merchant order identifier
     * @return the resulting {@code ResponseEntity<String>} value
     */
    @GetExchange(
            url = "/v1/order/posEnquiry/{orderId}",
            accept = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> enquireOrder(
            @RequestHeader("secretKey") String secretKey,
            @RequestHeader("eccode") String eccode,
            @PathVariable("orderId") String orderId);
}
