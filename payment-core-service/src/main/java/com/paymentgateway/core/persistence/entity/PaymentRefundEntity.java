package com.paymentgateway.core.persistence.entity;

import com.paymentgateway.common.RefundStatus;
import com.paymentgateway.persistence.BaseAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;

/**
 * Represents the persisted payment refund state.
 */
@Entity
@Table(name="payment_refund", uniqueConstraints=@UniqueConstraint(name="uq_refund_merchant_ref",columnNames={"merchant_id","merchant_refund_id"}))
public class PaymentRefundEntity extends BaseAuditableEntity {
    @Id @Column(name="refund_id") private UUID refundId;
    @Column(name="payment_id",nullable=false) private UUID paymentId;
    @Column(name="merchant_id",nullable=false) private UUID merchantId;
    @Column(name="merchant_refund_id",nullable=false,length=128) private String merchantRefundId;
    @Column(name="amount_minor",nullable=false) private long amountMinor;
    @Column(nullable=false,length=3) private String currency;
    @Column(name="provider_code",nullable=false,length=64) private String providerCode;
    @Column(name="provider_refund_reference_id",length=200) private String providerRefundReferenceId;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=32) private RefundStatus status;
    @Column(name="provider_response_code",length=64) private String providerResponseCode;
    @Column(name="provider_response_message",length=1000) private String providerResponseMessage;
    /**
     * Creates a new {@code PaymentRefundEntity} instance.
     */
    protected PaymentRefundEntity(){}
    /**
     * Executes the requested operation.
     *
     * @param refundId the refund identifier
     * @param p the payment entity
     * @param merchantRefundId the merchant refund identifier value
     * @param amountMinor the payment amount in minor currency units
     * @return the resulting {@code PaymentRefundEntity} value
     */
    public static PaymentRefundEntity requested(UUID refundId,PaymentEntity p,String merchantRefundId,long amountMinor){
        var r=new PaymentRefundEntity(); r.refundId=refundId; r.paymentId=p.getPaymentId(); r.merchantId=p.getMerchantId();
        r.merchantRefundId=merchantRefundId; r.amountMinor=amountMinor; r.currency=p.getCurrency(); r.providerCode=p.getSelectedProviderCode(); r.status=RefundStatus.REQUESTED; return r;
    }
    /**
     * Executes the apply operation.
     *
     * @param result the operation result
     */
    public void apply(com.paymentgateway.contract.provider.ProviderRefundResult result){
        this.providerRefundReferenceId=result.providerRefundReferenceId(); this.providerResponseCode=result.responseCode(); this.providerResponseMessage=result.responseMessage();
        this.status=switch(result.outcome()){
            case REFUNDED, SUCCESS -> RefundStatus.SUCCEEDED;
            case PENDING -> RefundStatus.PENDING;
            case UNKNOWN, NOT_FOUND -> RefundStatus.UNKNOWN;
            case REJECTED, VOIDED -> RefundStatus.FAILED;
        };
    }
    /**
     * Retrieves refund identifier.
     *
     * @return the resulting {@code UUID} value
     */
    public UUID getRefundId(){return refundId;} /**
     * Retrieves payment identifier.
     *
     * @return the resulting {@code UUID} value
     */
    public UUID getPaymentId(){return paymentId;} /**
     * Retrieves merchant identifier.
     *
     * @return the resulting {@code UUID} value
     */
    public UUID getMerchantId(){return merchantId;}
    /**
     * Retrieves merchant refund identifier.
     *
     * @return the resulting get merchant refund identifier text
     */
    public String getMerchantRefundId(){return merchantRefundId;} /**
     * Retrieves amount minor.
     *
     * @return the resulting get amount minor value
     */
    public long getAmountMinor(){return amountMinor;} /**
     * Retrieves currency.
     *
     * @return the resulting get currency text
     */
    public String getCurrency(){return currency;}
    /**
     * Retrieves provider code.
     *
     * @return the resulting get provider code text
     */
    public String getProviderCode(){return providerCode;} /**
     * Retrieves provider refund reference identifier.
     *
     * @return the resulting get provider refund reference identifier text
     */
    public String getProviderRefundReferenceId(){return providerRefundReferenceId;} /**
     * Retrieves status.
     *
     * @return the resulting {@code RefundStatus} value
     */
    public RefundStatus getStatus(){return status;}
    /**
     * Retrieves provider response message.
     *
     * @return the resulting get provider response message text
     */
    public String getProviderResponseMessage(){return providerResponseMessage;}
}
