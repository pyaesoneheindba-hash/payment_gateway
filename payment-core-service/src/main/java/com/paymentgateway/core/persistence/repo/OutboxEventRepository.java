package com.paymentgateway.core.persistence.repo;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.paymentgateway.core.persistence.entity.OutboxEventEntity;

/**
 * Provides persistence access for outbox event data.
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {
}
