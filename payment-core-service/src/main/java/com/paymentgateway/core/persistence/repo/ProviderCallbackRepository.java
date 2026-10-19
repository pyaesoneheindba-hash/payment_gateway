package com.paymentgateway.core.persistence.repo;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.paymentgateway.core.persistence.entity.ProviderCallbackEntity;

/**
 * Provides persistence access for provider callback data.
 */
public interface ProviderCallbackRepository extends JpaRepository<ProviderCallbackEntity, UUID> {
	/**
	 * Executes the exists by provider code and dedupe key operation.
	 *
	 * @param providerCode the payment provider code
	 * @param dedupeKey    the dedupe key value
	 * @return {@code true} when the condition is satisfied; otherwise {@code false}
	 */
	boolean existsByProviderCodeAndDedupeKey(String providerCode, String dedupeKey);

	/**
	 * Finds by provider code and dedupe key.
	 *
	 * @param providerCode the payment provider code
	 * @param dedupeKey    the dedupe key value
	 * @return the matching value when present
	 */
	Optional<ProviderCallbackEntity> findByProviderCodeAndDedupeKey(String providerCode, String dedupeKey);
}
