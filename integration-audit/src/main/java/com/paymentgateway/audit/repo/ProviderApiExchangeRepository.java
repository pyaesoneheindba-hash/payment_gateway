package com.paymentgateway.audit.repo;
import com.paymentgateway.audit.entity.ProviderApiExchangeEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
/**
 * Provides persistence access for provider API exchange data.
 */
public interface ProviderApiExchangeRepository extends JpaRepository<ProviderApiExchangeEntity, UUID> {}
