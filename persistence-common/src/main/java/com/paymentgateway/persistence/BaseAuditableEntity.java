package com.paymentgateway.persistence;

import java.time.Instant;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

/**
 * Shared persistence base for every JPA entity in the project.
 *
 * <p>
 * {@code created_at} and {@code updated_at} are populated automatically by
 * Spring Data JPA auditing for normal entity persistence. {@code version} is
 * managed by JPA optimistic locking.
 * </p>
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseAuditableEntity {
	@Version
	@Column(name = "version", nullable = false)
	private long version;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	/**
	 * Retrieves version.
	 * 
	 * @return the resulting get version value
	 */
	public long getVersion() {
		return version;
	}

	/**
	 * Retrieves created at.
	 *
	 * @return the resulting {@code Instant} value
	 */
	public Instant getCreatedAt() {
		return createdAt;
	}

	/**
	 * Retrieves updated at.
	 *
	 * @return the resulting {@code Instant} value
	 */
	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
