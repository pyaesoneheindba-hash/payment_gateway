package com.paymentgateway.core.persistence.repo;

import com.paymentgateway.common.RefundStatus;
import com.paymentgateway.core.persistence.entity.PaymentRefundEntity;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Provides persistence access for payment refund data.
 */
public interface PaymentRefundRepository extends JpaRepository<PaymentRefundEntity, UUID> {
    /**
     * Finds by merchant identifier and merchant refund identifier.
     *
     * @param merchantId the merchant identifier
     * @param merchantRefundId the merchant refund identifier value
     * @return the matching value when present
     */
    Optional<PaymentRefundEntity> findByMerchantIdAndMerchantRefundId(UUID merchantId, String merchantRefundId);

    /**
     * Executes the sum amount by payment identifier and status operation.
     *
     * @param paymentId the payment identifier
     * @param status the payment or delivery status
     * @return the resulting sum amount by payment identifier and status value
     */
    @Query("select coalesce(sum(r.amountMinor),0) from PaymentRefundEntity r where r.paymentId=:paymentId and r.status=:status")
    long sumAmountByPaymentIdAndStatus(@Param("paymentId") UUID paymentId, @Param("status") RefundStatus status);

    /**
     * Executes the sum reserved amount by payment identifier operation.
     *
     * @param paymentId the payment identifier
     * @param statuses the statuses value
     * @return the resulting sum reserved amount by payment identifier value
     */
    @Query("select coalesce(sum(r.amountMinor),0) from PaymentRefundEntity r where r.paymentId=:paymentId and r.status in :statuses")
    long sumReservedAmountByPaymentId(@Param("paymentId") UUID paymentId,
            @Param("statuses") Collection<RefundStatus> statuses);
}
