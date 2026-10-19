package com.paymentgateway.audit.repo;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.paymentgateway.audit.entity.ProviderApiExchangeEntity;

/**
 * Provides persistence access for provider API exchange data.
 */
public interface ProviderApiExchangeRepository extends JpaRepository<ProviderApiExchangeEntity, UUID> {
}
