package com.paymentgateway.provider.abank;

import com.paymentgateway.audit.service.ProviderExchangeAuditService;
import com.paymentgateway.audit.util.AuditJson;
import com.paymentgateway.provider.abank.api.AbankCreateOrderRequest;
import com.paymentgateway.provider.abank.api.AbankCreateOrderResponse;
import com.paymentgateway.provider.abank.api.AbankEnquiryResponse;
import com.paymentgateway.provider.abank.http.AbankHttpService;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.HttpStatusCodeException;

/**
 * Auditing wrapper around the declarative {@link AbankHttpService}.
 *
 * <p>The actual ABank endpoints are declared on a Spring HTTP Service
 * interface. This class keeps payment/provider audit concerns separate from
 * the HTTP contract.</p>
 */
@Component
public class AbankApiClient {
    private static final Logger log = LoggerFactory.getLogger(AbankApiClient.class);

    private final AbankHttpService httpService;
    private final AbankProperties properties;
    private final ProviderExchangeAuditService audit;

    /**
     * Creates a new {@code AbankApiClient} instance.
     *
     * @param httpService the HTTP service value
     * @param properties the properties value
     * @param audit the audit value
     */
    public AbankApiClient(
            AbankHttpService httpService,
            AbankProperties properties,
            ProviderExchangeAuditService audit) {
        this.httpService = httpService;
        this.properties = properties;
        this.audit = audit;
    }

    /**
     * Executes the create operation.
     *
     * @param paymentId the payment identifier
     * @param request the current request or request payload
     * @return the resulting {@code AbankCreateOrderResponse} value
     */
    public AbankCreateOrderResponse create(UUID paymentId, AbankCreateOrderRequest request) {
        String path = "/v1/order/create";
        String requestBody = AuditJson.write(request);
        UUID exchangeId = audit.start(
                paymentId,
                "ABANK_APLUS",
                "CREATE_ORDER",
                request.requestNo(),
                request.orderId(),
                "POST",
                properties.baseUrl() + path,
                redactedHeaders(),
                requestBody);

        try {
            ResponseEntity<String> http = httpService.createOrder(
                    properties.secretKey(), properties.eccode(), request);
            String body = http.getBody();
            var parsed = AuditJson.read(body, AbankCreateOrderResponse.class);
            safeComplete(
                    exchangeId,
                    http.getStatusCode().value(),
                    AuditJson.write(http.getHeaders()),
                    body,
                    parsed == null || parsed.respondCode() == null ? null : String.valueOf(parsed.respondCode()),
                    parsed == null ? null : parsed.respondMessage(),
                    "RECEIVED");
            return parsed;
        } catch (HttpStatusCodeException e) {
            safeFail(
                    exchangeId,
                    e.getStatusCode().value(),
                    AuditJson.write(e.getResponseHeaders()),
                    e.getResponseBodyAsString(),
                    e,
                    e.getStatusCode().is5xxServerError() ? "UNKNOWN" : "REJECTED");
            throw e;
        } catch (ResourceAccessException e) {
            safeFail(exchangeId, null, null, null, e, "UNKNOWN");
            throw e;
        } catch (RuntimeException e) {
            safeFail(exchangeId, null, null, null, e, "ERROR");
            throw e;
        }
    }

    /**
     * Executes the enquire operation.
     *
     * @param paymentId the payment identifier
     * @param orderId the provider or merchant order identifier
     * @return the resulting {@code AbankEnquiryResponse} value
     */
    public AbankEnquiryResponse enquire(UUID paymentId, String orderId) {
        String path = "/v1/order/posEnquiry/" + orderId;
        UUID exchangeId = audit.start(
                paymentId,
                "ABANK_APLUS",
                "ENQUIRY_ORDER",
                null,
                orderId,
                "GET",
                properties.baseUrl() + path,
                redactedHeaders(),
                null);

        try {
            ResponseEntity<String> http = httpService.enquireOrder(
                    properties.secretKey(), properties.eccode(), orderId);
            String body = http.getBody();
            var parsed = AuditJson.read(body, AbankEnquiryResponse.class);
            safeComplete(
                    exchangeId,
                    http.getStatusCode().value(),
                    AuditJson.write(http.getHeaders()),
                    body,
                    parsed == null || parsed.respondCode() == null ? null : String.valueOf(parsed.respondCode()),
                    parsed == null ? null : parsed.respondMessage(),
                    "RECEIVED");
            return parsed;
        } catch (HttpStatusCodeException e) {
            safeFail(
                    exchangeId,
                    e.getStatusCode().value(),
                    AuditJson.write(e.getResponseHeaders()),
                    e.getResponseBodyAsString(),
                    e,
                    e.getStatusCode().is5xxServerError() ? "UNKNOWN" : "REJECTED");
            throw e;
        } catch (ResourceAccessException e) {
            safeFail(exchangeId, null, null, null, e, "UNKNOWN");
            throw e;
        } catch (RuntimeException e) {
            safeFail(exchangeId, null, null, null, e, "ERROR");
            throw e;
        }
    }

    /**
     * Executes the safe complete operation.
     *
     * @param exchangeId the exchange identifier identifier
     * @param status the payment or delivery status
     * @param responseHeaders the response headers value
     * @param responseBody the response body value
     * @param providerResponseCode the provider response code value
     * @param providerResponseMessage the provider response message value
     * @param outcome the outcome value
     */
    private void safeComplete(
            UUID exchangeId,
            int status,
            String responseHeaders,
            String responseBody,
            String providerResponseCode,
            String providerResponseMessage,
            String outcome) {
        try {
            audit.complete(
                    exchangeId,
                    status,
                    responseHeaders,
                    responseBody,
                    providerResponseCode,
                    providerResponseMessage,
                    outcome);
        } catch (RuntimeException e) {
            log.error("ABank call succeeded but audit completion failed for {}", exchangeId, e);
        }
    }

    /**
     * Executes the safe fail operation.
     *
     * @param exchangeId the exchange identifier identifier
     * @param status the payment or delivery status
     * @param responseHeaders the response headers value
     * @param responseBody the response body value
     * @param error the failure that triggered error handling
     * @param outcome the outcome value
     */
    private void safeFail(
            UUID exchangeId,
            Integer status,
            String responseHeaders,
            String responseBody,
            Throwable error,
            String outcome) {
        try {
            audit.fail(exchangeId, status, responseHeaders, responseBody, error, outcome);
        } catch (RuntimeException e) {
            log.error("ABank call failed and audit finalization also failed for {}", exchangeId, e);
        }
    }

    /**
     * Executes the redacted headers operation.
     *
     * @return the resulting redacted headers text
     */
    private String redactedHeaders() {
        return AuditJson.write(Map.of(
                "Content-Type", "application/json",
                "secretKey", "***REDACTED***",
                "eccode", "***REDACTED***"));
    }
}
