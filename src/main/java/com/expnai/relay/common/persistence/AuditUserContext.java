package com.expnai.relay.common.persistence;

import java.util.Optional;

/**
 * Request-scoped audit user holder used by entities in {@code @PrePersist}/{@code @PreUpdate}.
 */
public final class AuditUserContext {

	private static final ThreadLocal<Long> CURRENT_USER_ID = new ThreadLocal<>();

	private AuditUserContext() {
	}

	public static void setCurrentUserId(Long userId) {
		if (userId == null) {
			CURRENT_USER_ID.remove();
			return;
		}
		CURRENT_USER_ID.set(userId);
	}

	public static Optional<Long> getCurrentUserId() {
		return Optional.ofNullable(CURRENT_USER_ID.get());
	}

	public static void clear() {
		CURRENT_USER_ID.remove();
	}
}
