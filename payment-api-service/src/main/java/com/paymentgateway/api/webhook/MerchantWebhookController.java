package com.paymentgateway.api.webhook;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes HTTP endpoints for merchant webhook operations.
 */
@RestController
@RequestMapping("/api/v1/webhooks/endpoints")
public class MerchantWebhookController {
    private static final String AUTH_HEADER = "X-Payment-Gateway-Auth-Mode";
    private static final String TRUSTED_AUTH = "MERCHANT_SIGNATURE";

    private final MerchantWebhookEndpointStore store;
    private final WebhookUrlPolicy urlPolicy;

    /**
     * Creates a new {@code MerchantWebhookController} instance.
     *
     * @param store the store value
     * @param urlPolicy the URL policy value
     */
    public MerchantWebhookController(MerchantWebhookEndpointStore store, WebhookUrlPolicy urlPolicy) {
        this.store = store;
        this.urlPolicy = urlPolicy;
    }

    /**
     * Executes the register operation.
     *
     * @param merchantId the merchant identifier
     * @param authMode the trusted authentication mode set by the gateway
     * @param request the current request or request payload
     * @return the resulting {@code WebhookEndpointResponse} value
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WebhookEndpointResponse register(
            @RequestHeader("X-Merchant-Id") UUID merchantId,
            @RequestHeader(AUTH_HEADER) String authMode,
            @Valid @RequestBody WebhookEndpointRequest request) {
        requireTrusted(authMode);
        String url = urlPolicy.validateAndNormalize(request.url());
        return WebhookEndpointResponse.from(store.create(merchantId, url, request.description()));
    }

    /**
     * Executes the list operation.
     *
     * @param merchantId the merchant identifier
     * @param authMode the trusted authentication mode set by the gateway
     * @return the matching values
     */
    @GetMapping
    public List<WebhookEndpointResponse> list(
            @RequestHeader("X-Merchant-Id") UUID merchantId,
            @RequestHeader(AUTH_HEADER) String authMode) {
        requireTrusted(authMode);
        return store.list(merchantId).stream().map(WebhookEndpointResponse::from).toList();
    }

    /**
     * Executes the state operation.
     *
     * @param merchantId the merchant identifier
     * @param authMode the trusted authentication mode set by the gateway
     * @param endpointId the webhook endpoint identifier
     * @param status the payment or delivery status
     * @return the resulting {@code WebhookEndpointResponse} value
     */
    @PostMapping("/state")
    public WebhookEndpointResponse state(
            @RequestHeader("X-Merchant-Id") UUID merchantId,
            @RequestHeader(AUTH_HEADER) String authMode,
            @RequestParam UUID endpointId,
            @RequestParam String status) {
        requireTrusted(authMode);
        String normalized;
        if ("ACTIVE".equalsIgnoreCase(status)) normalized = "ACTIVE";
        else if ("PAUSED".equalsIgnoreCase(status)) normalized = "PAUSED";
        else throw new IllegalArgumentException("status must be ACTIVE or PAUSED");
        return WebhookEndpointResponse.from(store.changeStatus(endpointId, merchantId, normalized));
    }

    /**
     * Retrieves and validates trusted.
     *
     * @param authMode the trusted authentication mode set by the gateway
     */
    private static void requireTrusted(String authMode) {
        if (!TRUSTED_AUTH.equals(authMode)) {
            throw new IllegalArgumentException("Webhook endpoint management requires merchant Ed25519 authentication");
        }
    }
}
