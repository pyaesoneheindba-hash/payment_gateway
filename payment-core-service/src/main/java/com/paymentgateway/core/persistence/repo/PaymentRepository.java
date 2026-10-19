package com.paymentgateway.core.persistence.repo;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.paymentgateway.core.persistence.entity.PaymentEntity;

import jakarta.persistence.LockModeType;

/**
 * Provides persistence access for payment data.
 */
public interface PaymentRepository extends JpaRepository<PaymentEntity, UUID> {
	/**
	 * Finds by merchant identifier and merchant order identifier.
	 *
	 * @param merchantId      the merchant identifier
	 * @param merchantOrderId the merchant-defined order identifier
	 * @return the matching value when present
	 */
	Optional<PaymentEntity> findByMerchantIdAndMerchantOrderId(UUID merchantId, String merchantOrderId);

	/**
	 * Finds by selected provider code and provider order identifier.
	 *
	 * @param providerCode    the payment provider code
	 * @param providerOrderId the provider order identifier value
	 * @return the matching value when present
	 */
	Optional<PaymentEntity> findBySelectedProviderCodeAndProviderOrderId(String providerCode, String providerOrderId);

	/**
	 * Finds by identifier for update.
	 *
	 * @param id the resource identifier
	 * @return the matching value when present
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from PaymentEntity p where p.paymentId=:id")
	Optional<PaymentEntity> findByIdForUpdate(@Param("id") UUID id);
}
