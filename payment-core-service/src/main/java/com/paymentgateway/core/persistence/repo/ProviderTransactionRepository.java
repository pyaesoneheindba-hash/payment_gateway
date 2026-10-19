package com.paymentgateway.core.persistence.repo;

import com.paymentgateway.core.persistence.entity.ProviderTransactionEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Provides persistence access for provider transaction data.
 */
public interface ProviderTransactionRepository extends JpaRepository<ProviderTransactionEntity, UUID> {
}
