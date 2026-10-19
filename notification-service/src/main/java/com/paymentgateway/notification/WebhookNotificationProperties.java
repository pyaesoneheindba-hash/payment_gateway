package com.paymentgateway.notification;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds configuration properties for webhook notification.
 */
@Component
@ConfigurationProperties(prefix = "payment.webhook.delivery")
public class WebhookNotificationProperties {
    private boolean enabled = false;
    private String signingKeyId = "payment-gateway-webhook-2026-01";
    private String privateKeyPath = "keys/payment-gateway-webhook-private.pem";
    private boolean allowHttp = false;
    private boolean allowPrivateAddresses = false;
    private int connectTimeoutMs = 3000;
    private int requestTimeoutMs = 5000;
    private int deliveryBatchSize = 20;
    private int maxAttempts = 6;
    private long staleClaimSeconds = 60;
    private List<Long> retryDelaysSeconds = new ArrayList<>(List.of(10L, 30L, 120L, 600L, 1800L));

    /**
     * Determines whether enabled.
     *
     * @return {@code true} when the condition is satisfied; otherwise {@code false}
     */
    public boolean isEnabled() { return enabled; }
    /**
     * Executes the set enabled operation.
     *
     * @param enabled whether enabled is enabled
     */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    /**
     * Retrieves signing key identifier.
     *
     * @return the resulting get signing key identifier text
     */
    public String getSigningKeyId() { return signingKeyId; }
    /**
     * Executes the set signing key identifier operation.
     *
     * @param signingKeyId the signing key identifier value
     */
    public void setSigningKeyId(String signingKeyId) { this.signingKeyId = signingKeyId; }
    /**
     * Retrieves private key path.
     *
     * @return the resulting get private key path text
     */
    public String getPrivateKeyPath() { return privateKeyPath; }
    /**
     * Executes the set private key path operation.
     *
     * @param privateKeyPath the private key path value
     */
    public void setPrivateKeyPath(String privateKeyPath) { this.privateKeyPath = privateKeyPath; }
    /**
     * Determines whether allow HTTP.
     *
     * @return {@code true} when the condition is satisfied; otherwise {@code false}
     */
    public boolean isAllowHttp() { return allowHttp; }
    /**
     * Executes the set allow HTTP operation.
     *
     * @param allowHttp whether allow HTTP is enabled
     */
    public void setAllowHttp(boolean allowHttp) { this.allowHttp = allowHttp; }
    /**
     * Determines whether allow private addresses.
     *
     * @return {@code true} when the condition is satisfied; otherwise {@code false}
     */
    public boolean isAllowPrivateAddresses() { return allowPrivateAddresses; }
    /**
     * Executes the set allow private addresses operation.
     *
     * @param allowPrivateAddresses whether allow private addresses is enabled
     */
    public void setAllowPrivateAddresses(boolean allowPrivateAddresses) { this.allowPrivateAddresses = allowPrivateAddresses; }
    /**
     * Retrieves connect timeout ms.
     *
     * @return the resulting get connect timeout ms value
     */
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    /**
     * Executes the set connect timeout ms operation.
     *
     * @param connectTimeoutMs the connect timeout ms value
     */
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    /**
     * Retrieves request timeout ms.
     *
     * @return the resulting get request timeout ms value
     */
    public int getRequestTimeoutMs() { return requestTimeoutMs; }
    /**
     * Executes the set request timeout ms operation.
     *
     * @param requestTimeoutMs the request timeout ms value
     */
    public void setRequestTimeoutMs(int requestTimeoutMs) { this.requestTimeoutMs = requestTimeoutMs; }
    /**
     * Retrieves delivery batch size.
     *
     * @return the resulting get delivery batch size value
     */
    public int getDeliveryBatchSize() { return deliveryBatchSize; }
    /**
     * Executes the set delivery batch size operation.
     *
     * @param deliveryBatchSize the delivery batch size value
     */
    public void setDeliveryBatchSize(int deliveryBatchSize) { this.deliveryBatchSize = deliveryBatchSize; }
    /**
     * Retrieves max attempts.
     *
     * @return the resulting get max attempts value
     */
    public int getMaxAttempts() { return maxAttempts; }
    /**
     * Executes the set max attempts operation.
     *
     * @param maxAttempts the max attempts value
     */
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    /**
     * Retrieves stale claim seconds.
     *
     * @return the resulting get stale claim seconds value
     */
    public long getStaleClaimSeconds() { return staleClaimSeconds; }
    /**
     * Executes the set stale claim seconds operation.
     *
     * @param staleClaimSeconds the stale claim seconds value
     */
    public void setStaleClaimSeconds(long staleClaimSeconds) { this.staleClaimSeconds = staleClaimSeconds; }
    /**
     * Retrieves retry delays seconds.
     *
     * @return the matching values
     */
    public List<Long> getRetryDelaysSeconds() { return retryDelaysSeconds; }
    /**
     * Executes the set retry delays seconds operation.
     *
     * @param retryDelaysSeconds the retry delays seconds value
     */
    public void setRetryDelaysSeconds(List<Long> retryDelaysSeconds) {
        this.retryDelaysSeconds = retryDelaysSeconds == null ? new ArrayList<>() : new ArrayList<>(retryDelaysSeconds);
    }

    /**
     * Executes the retry delay for attempt operation.
     *
     * @param attemptCount the attempt count value
     * @return the resulting retry delay for attempt value
     */
    public long retryDelayForAttempt(int attemptCount) {
        if (retryDelaysSeconds.isEmpty()) return 60;
        int index = Math.max(0, Math.min(attemptCount - 1, retryDelaysSeconds.size() - 1));
        return Math.max(1, retryDelaysSeconds.get(index));
    }
}
