package com.expnai.relay.common.persistence;

import java.time.Instant;

import org.hibernate.annotations.SQLRestriction;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.Getter;
import lombok.Setter;

/**
 * Soft-delete flag {@code is_enable}. {@link SQLRestriction} uses boolean {@code true} for PostgreSQL.
 * <p>
 * {@code created_by} / {@code updated_by} are optional user ids; filled from {@link AuditUserContext}
 * when present.
 */
@Getter
@SQLRestriction("is_enable = true")
@MappedSuperclass
public abstract class MasterEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Setter
	@Column(name = "created_by")
	private Long createdBy;

	@Setter
	@Column(name = "updated_by")
	private Long updatedBy;

	@JsonIgnore
	@Column(name = "is_enable", nullable = false)
	private boolean isEnable = true;

	@PrePersist
	protected void onCreate() {
		Instant now = Instant.now();
		this.createdAt = now;
		this.updatedAt = now;
		AuditUserContext.getCurrentUserId().ifPresent(userId -> {
			if (this.createdBy == null) {
				this.createdBy = userId;
			}
			this.updatedBy = userId;
		});
	}

	@PreUpdate
	protected void onUpdate() {
		this.updatedAt = Instant.now();
		AuditUserContext.getCurrentUserId().ifPresent(userId -> this.updatedBy = userId);
	}

	/** Soft delete: disables the row; default queries ignore it via {@link SQLRestriction}. */
	public void delete() {
		this.isEnable = false;
		onUpdate();
	}

	public void setEnable(boolean isEnable) {
		this.isEnable = isEnable;
	}

	public void setId(Long id) {
		this.id = id;
	}
}
