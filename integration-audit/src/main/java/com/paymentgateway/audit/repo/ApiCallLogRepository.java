package com.paymentgateway.audit.repo;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.paymentgateway.audit.entity.ApiCallLogEntity;

/**
 * Provides persistence access for API call log data.
 */
public interface ApiCallLogRepository extends JpaRepository<ApiCallLogEntity, UUID> {
}
