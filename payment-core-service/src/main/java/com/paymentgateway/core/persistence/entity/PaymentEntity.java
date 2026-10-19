package com.paymentgateway.core.persistence.entity;

import com.paymentgateway.common.PaymentStatus;
import com.paymentgateway.core.domain.PaymentStateTransitions;
import com.paymentgateway.persistence.BaseAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Represents the persisted payment state.
 */
@Entity
@Table(name = "payment", uniqueConstraints = @UniqueConstraint(name = "uq_payment_merchant_order", columnNames = {"merchant_id", "merchant_order_id"}))
public class PaymentEntity extends BaseAuditableEntity {
    @Id @Column(name = "payment_id", nullable = false) private UUID paymentId;
    @Column(name = "merchant_id", nullable = false) private UUID merchantId;
    @Column(name = "merchant_order_id", nullable = false, length = 128) private String merchantOrderId;
    @Column(name = "amount_minor", nullable = false) private long amountMinor;
    @Column(nullable = false, length = 3) private String currency;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private PaymentStatus status;
    @Column(name = "selected_provider_code", length = 64) private String selectedProviderCode;
    @Column(name = "provider_order_id", length = 64) private String providerOrderId;
    @Column(name = "provider_reference_id", length = 200) private String providerReferenceId;
    @Column(name = "provider_order_date") private LocalDate providerOrderDate;
    @Column(name = "provider_next_action", length = 32) private String providerNextAction;
    @Column(name = "qr_payload", columnDefinition = "text") private String qrPayload;
    @Column(name = "provider_redirect_url", length = 2000) private String providerRedirectUrl;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "provider_form", columnDefinition = "jsonb") private Map<String, String> providerForm;
    @Column(name = "presentation_mode", nullable = false, length = 20) private String presentationMode = "QR_STRING";
    @Column(name = "expires_at") private Instant expiresAt;

    /**
     * Creates a new {@code PaymentEntity} instance.
     */
    protected PaymentEntity() {}

    /**
     * Executes the initiated operation.
     *
     * @param id the resource identifier
     * @param merchantId the merchant identifier
     * @param merchantOrderId the merchant-defined order identifier
     * @param amountMinor the payment amount in minor currency units
     * @param currency the ISO currency code
     * @return the resulting {@code PaymentEntity} value
     */
    public static PaymentEntity initiated(UUID id, UUID merchantId, String merchantOrderId, long amountMinor, String currency) {
        PaymentEntity p = new PaymentEntity();
        p.paymentId = id;
        p.merchantId = merchantId;
        p.merchantOrderId = merchantOrderId;
        p.amountMinor = amountMinor;
        p.currency = currency;
        p.status = PaymentStatus.INITIATED;
        return p;
    }

    /**
     * Executes the mark provider requested operation.
     *
     * @param providerCode the payment provider code
     * @param providerOrderId the provider order identifier value
     */
    public void markProviderRequested(String providerCode, String providerOrderId) {
        transition(PaymentStatus.PROVIDER_REQUESTED);
        this.selectedProviderCode = providerCode;
        this.providerOrderId = providerOrderId;
        this.providerOrderDate = LocalDate.now(ZoneId.of("Asia/Yangon"));
    }

    /**
     * Applies initiation.
     *
     * @param providerOrderId the provider order identifier value
     * @param providerReferenceId the provider reference identifier value
     * @param nextAction the next action value
     * @param qr the QR value
     * @param redirectUrl the redirect URL value
     * @param formFields the form fields value
     * @param newStatus the new status value
     */
    public void applyInitiation(String providerOrderId, String providerReferenceId, String nextAction,
            String qr, String redirectUrl, Map<String, String> formFields, PaymentStatus newStatus) {
        transition(newStatus);
        this.providerOrderId = providerOrderId;
        if (providerReferenceId != null && !providerReferenceId.isBlank()) this.providerReferenceId = providerReferenceId;
        this.providerNextAction = nextAction;
        this.qrPayload = qr;
        this.providerRedirectUrl = redirectUrl;
        this.providerForm = formFields;
    }

    /**
     * Executes the attach provider reference if absent operation.
     *
     * @param reference the reference value
     */
    public void attachProviderReferenceIfAbsent(String reference) {
        if ((providerReferenceId == null || providerReferenceId.isBlank()) && reference != null && !reference.isBlank()) {
            providerReferenceId = reference;
        }
    }

    /**
     * Executes the mark unknown operation.
     *
     * @param providerOrderId the provider order identifier value
     */
    public void markUnknown(String providerOrderId) {
        this.providerOrderId = providerOrderId;
        transition(PaymentStatus.UNKNOWN);
    }

    /**
     * Executes the mark failed operation.
     *
     * @param providerOrderId the provider order identifier value
     */
    public void markFailed(String providerOrderId) {
        this.providerOrderId = providerOrderId;
        transition(PaymentStatus.FAILED);
    }

    /** Applies a status that has been independently verified with the provider. */
    public void applyVerifiedStatus(PaymentStatus newStatus) {
        transition(newStatus);
    }

    /**
     * Configures checkout.
     *
     * @param mode the mode value
     * @param expiresAt the expires at timestamp
     */
    public void configureCheckout(String mode, Instant expiresAt) {
        if (!"QR_STRING".equals(mode) && !"HOSTED_PAGE".equals(mode)) {
            throw new IllegalArgumentException("Unsupported presentation mode: " + mode);
        }
        if (expiresAt == null || !expiresAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException("Payment expiry must be in the future");
        }
        this.presentationMode = mode;
        this.expiresAt = expiresAt;
    }

    /**
     * Expires if due.
     *
     * @param now the now timestamp
     * @return {@code true} when the condition is satisfied; otherwise {@code false}
     */
    public boolean expireIfDue(Instant now) {
        if (expiresAt == null || expiresAt.isAfter(now)) return false;
        if (status == PaymentStatus.INITIATED || status == PaymentStatus.PROVIDER_REQUESTED
                || status == PaymentStatus.QR_REQUESTED || status == PaymentStatus.QR_GENERATED
                || status == PaymentStatus.ACTION_REQUIRED || status == PaymentStatus.PENDING
                || status == PaymentStatus.UNKNOWN) {
            transition(PaymentStatus.EXPIRED);
            return true;
        }
        return false;
    }

    /**
     * Executes the transition operation.
     *
     * @param newStatus the new status value
     */
    private void transition(PaymentStatus newStatus) {
        PaymentStateTransitions.requireAllowed(this.status, newStatus);
        this.status = newStatus;
    }

    /**
     * Retrieves payment identifier.
     *
     * @return the resulting {@code UUID} value
     */
    public UUID getPaymentId() { return paymentId; }
    /**
     * Retrieves merchant identifier.
     *
     * @return the resulting {@code UUID} value
     */
    public UUID getMerchantId() { return merchantId; }
    /**
     * Retrieves merchant order identifier.
     *
     * @return the resulting get merchant order identifier text
     */
    public String getMerchantOrderId() { return merchantOrderId; }
    /**
     * Retrieves amount minor.
     *
     * @return the resulting get amount minor value
     */
    public long getAmountMinor() { return amountMinor; }
    /**
     * Retrieves currency.
     *
     * @return the resulting get currency text
     */
    public String getCurrency() { return currency; }
    /**
     * Retrieves status.
     *
     * @return the resulting {@code PaymentStatus} value
     */
    public PaymentStatus getStatus() { return status; }
    /**
     * Retrieves selected provider code.
     *
     * @return the resulting get selected provider code text
     */
    public String getSelectedProviderCode() { return selectedProviderCode; }
    /**
     * Retrieves provider order identifier.
     *
     * @return the resulting get provider order identifier text
     */
    public String getProviderOrderId() { return providerOrderId; }
    /**
     * Retrieves provider reference identifier.
     *
     * @return the resulting get provider reference identifier text
     */
    public String getProviderReferenceId() { return providerReferenceId; }
    /**
     * Retrieves provider order date.
     *
     * @return the resulting {@code LocalDate} value
     */
    public LocalDate getProviderOrderDate() { return providerOrderDate; }
    /**
     * Retrieves provider next action.
     *
     * @return the resulting get provider next action text
     */
    public String getProviderNextAction() { return providerNextAction; }
    /**
     * Retrieves QR payload.
     *
     * @return the resulting get QR payload text
     */
    public String getQrPayload() { return qrPayload; }
    /**
     * Retrieves provider redirect URL.
     *
     * @return the resulting get provider redirect URL text
     */
    public String getProviderRedirectUrl() { return providerRedirectUrl; }
    /**
     * Retrieves provider form.
     *
     * @return the resulting key-value data
     */
    public Map<String, String> getProviderForm() { return providerForm; }
    /**
     * Retrieves presentation mode.
     *
     * @return the resulting get presentation mode text
     */
    public String getPresentationMode() { return presentationMode; }
    /**
     * Retrieves expires at.
     *
     * @return the resulting {@code Instant} value
     */
    public Instant getExpiresAt() { return expiresAt; }
}
