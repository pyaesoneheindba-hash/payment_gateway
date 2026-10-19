package com.paymentgateway.core.persistence.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import com.paymentgateway.core.persistence.entity.PaymentProviderEntity;

/**
 * Provides persistence access for payment provider data.
 */
public interface PaymentProviderRepository extends JpaRepository<PaymentProviderEntity, String> {
}
