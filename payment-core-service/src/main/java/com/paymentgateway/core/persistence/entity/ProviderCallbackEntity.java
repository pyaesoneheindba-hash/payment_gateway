package com.paymentgateway.core.persistence.entity;

import com.paymentgateway.contract.payment.ProviderCallbackCommand;
import com.paymentgateway.persistence.BaseAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * Represents the persisted provider callback state.
 */
@Entity
@Table(name="provider_callback", uniqueConstraints=@UniqueConstraint(name="uq_provider_callback",columnNames={"provider_code","dedupe_key"}))
public class ProviderCallbackEntity extends BaseAuditableEntity {
    @Id @Column(name="callback_id") private UUID callbackId;
    @Column(name="provider_code",nullable=false,length=64) private String providerCode;
    @Column(name="dedupe_key",nullable=false,length=256) private String dedupeKey;
    @Column(name="provider_order_id",length=64) private String providerOrderId;
    @Column(name="provider_reference_id",length=200) private String providerReferenceId;
    @Column(name="callback_type",nullable=false,length=64) private String callbackType;
    @Column(name="amount",length=64) private String amount;
    @Column(name="provider_status",length=64) private String providerStatus;
    @Column(name="provider_response_code",length=64) private String providerResponseCode;
    @Column(name="provider_transaction_id",length=200) private String providerTransactionId;
    @Column(name="approval_code",length=64) private String approvalCode;
    @Column(name="bill_no",length=200) private String billNo;
    @Column(name="end_to_end_id",length=200) private String endToEndId;
    @Column(name="transaction_datetime",length=64) private String transactionDateTime;
    @Column(name="institution_name",length=255) private String institutionName;
    @Column(name="masked_pan",length=32) private String maskedPan;
    @Column(name="error_code",length=100) private String errorCode;
    @Column(name="error_description",columnDefinition="TEXT") private String errorDescription;
    @Column(name="signature_verified") private Boolean signatureVerified;
    @Column(name="raw_payload_json",columnDefinition="TEXT",nullable=false) private String rawPayloadJson;
    @Column(name="payload_hash",nullable=false,length=64) private String payloadHash;
    @Column(name="processing_status",nullable=false,length=32) private String processingStatus;
    @Column(name="processing_message",columnDefinition="TEXT") private String processingMessage;
    @Column(name="verified_provider_status",length=64) private String verifiedProviderStatus;
    @Column(name="received_at",nullable=false) private Instant receivedAt;
    @Column(name="processed_at") private Instant processedAt;

    /**
     * Creates a new {@code ProviderCallbackEntity} instance.
     */
    protected ProviderCallbackEntity(){}

    /**
     * Executes the received operation.
     *
     * @param c the provider callback command
     * @param hash the hash value
     * @return the resulting {@code ProviderCallbackEntity} value
     */
    public static ProviderCallbackEntity received(ProviderCallbackCommand c, String hash) {
        var e=new ProviderCallbackEntity();
        e.callbackId=UUID.randomUUID(); e.providerCode=c.providerCode(); e.dedupeKey=c.dedupeKey();
        e.providerOrderId=c.orderId(); e.providerReferenceId=c.providerReferenceId(); e.callbackType=c.callbackType(); e.amount=c.amount();
        e.providerStatus=c.status(); e.providerResponseCode=c.responseCode(); e.providerTransactionId=c.transactionId();
        e.approvalCode=c.approvalCode(); e.billNo=c.billNo(); e.endToEndId=c.endToEndId();
        e.transactionDateTime=c.transactionDateTime(); e.institutionName=c.institutionName(); e.maskedPan=c.maskedPan();
        e.errorCode=c.errorCode(); e.errorDescription=c.errorDescription(); e.signatureVerified=c.signatureVerified();
        e.rawPayloadJson=c.rawPayloadJson()==null?"{}":c.rawPayloadJson(); e.payloadHash=hash;
        e.processingStatus="RECEIVED"; e.receivedAt=Instant.now(); return e;
    }
    /**
     * Executes the processed operation.
     *
     * @param verifiedProviderStatus the verified provider status value
     * @param message the message value
     */
    public void processed(String verifiedProviderStatus, String message) {
        this.processingStatus="PROCESSED"; this.verifiedProviderStatus=verifiedProviderStatus;
        this.processingMessage=message; this.processedAt=Instant.now();
    }
    /**
     * Executes the failed operation.
     *
     * @param message the message value
     */
    public void failed(String message) {
        this.processingStatus="FAILED"; this.processingMessage=message; this.processedAt=Instant.now();
    }
}
