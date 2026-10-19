package com.paymentgateway.audit.repo;
import com.paymentgateway.audit.entity.ApiCallLogEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
/**
 * Provides persistence access for API call log data.
 */
public interface ApiCallLogRepository extends JpaRepository<ApiCallLogEntity, UUID> {}
