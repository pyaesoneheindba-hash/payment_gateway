package com.paymentgateway.core.persistence.repo;
import com.paymentgateway.core.persistence.entity.PaymentProviderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
/**
 * Provides persistence access for payment provider data.
 */
public interface PaymentProviderRepository extends JpaRepository<PaymentProviderEntity,String> {}
