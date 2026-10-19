package com.paymentgateway.core.persistence.repo;

import com.paymentgateway.core.persistence.entity.IdempotencyRecordEntity;
import com.paymentgateway.core.persistence.entity.IdempotencyRecordId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Provides persistence access for idempotency record data.
 */
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecordEntity, IdempotencyRecordId> {
    /**
     * Finds by resource identifier.
     *
     * @param resourceId the resource identifier identifier
     * @return the matching value when present
     */
    Optional<IdempotencyRecordEntity> findByResourceId(UUID resourceId);

    /**
     * Finds by operation and resource identifier.
     *
     * @param operation the operation value
     * @param resourceId the resource identifier identifier
     * @return the matching value when present
     */
    @Query("select i from IdempotencyRecordEntity i where i.id.operation=:operation and i.resourceId=:resourceId")
    Optional<IdempotencyRecordEntity> findByOperationAndResourceId(@Param("operation") String operation,@Param("resourceId") UUID resourceId);

    /**
     * PostgreSQL atomic reservation. Using ON CONFLICT avoids turning the whole
     * transaction into an aborted state when two requests race on the same
     * idempotency key.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO idempotency_record
                (merchant_id, operation, idempotency_key, request_hash, status, resource_id, created_at)
            VALUES
                (:merchantId, :operation, :idempotencyKey, :requestHash, 'PROCESSING', :resourceId, :createdAt)
            ON CONFLICT (merchant_id, operation, idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("merchantId") UUID merchantId,
            @Param("operation") String operation,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestHash") String requestHash,
            @Param("resourceId") UUID resourceId,
            @Param("createdAt") Instant createdAt);
}
