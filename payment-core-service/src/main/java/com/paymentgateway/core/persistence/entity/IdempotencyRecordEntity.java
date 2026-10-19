package com.paymentgateway.core.persistence.entity;

import com.paymentgateway.persistence.BaseAuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Represents the persisted idempotency record state.
 */
@Entity
@Table(name="idempotency_record")
public class IdempotencyRecordEntity extends BaseAuditableEntity {
    @EmbeddedId private IdempotencyRecordId id;
    @Column(name="request_hash",nullable=false,length=64) private String requestHash;
    @Column(nullable=false,length=16) private String status;
    @Column(name="resource_id") private UUID resourceId;
    @Column(name="completed_at") private Instant completedAt;
    /**
     * Creates a new {@code IdempotencyRecordEntity} instance.
     */
    protected IdempotencyRecordEntity() {}
    /**
     * Executes the complete operation.
     */
    public void complete(){this.status="COMPLETED";this.completedAt=Instant.now();}
    /**
     * Retrieves request hash.
     *
     * @return the resulting get request hash text
     */
    public String getRequestHash(){return requestHash;} /**
     * Retrieves status.
     *
     * @return the resulting get status text
     */
    public String getStatus(){return status;} /**
     * Retrieves resource identifier.
     *
     * @return the resulting {@code UUID} value
     */
    public UUID getResourceId(){return resourceId;}
}
