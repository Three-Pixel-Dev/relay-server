package com.expnai.relay.common.specification;

import org.springframework.data.jpa.domain.Specification;

import com.expnai.relay.common.persistence.MasterEntity;

/**
 * Soft-delete: {@code MasterEntity.isEnable = true}.
 */
public final class MasterEntitySpecifications {

	private MasterEntitySpecifications() {
	}

	public static <T extends MasterEntity> Specification<T> isEnabled() {
		return (root, query, cb) -> cb.isTrue(root.get("isEnable"));
	}

	/**
	 * ANDs {@link #isEnabled()} when {@code entityClass} is a {@link MasterEntity} subtype.
	 */
	@SuppressWarnings("unchecked")
	public static <E> Specification<E> restrictToEnabledIfMasterEntity(Class<?> entityClass, Specification<E> spec) {
		if (entityClass == null || !MasterEntity.class.isAssignableFrom(entityClass)) {
			return spec;
		}
		Specification<E> enabled = (Specification<E>) isEnabled();
		return spec.and(enabled);
	}
}
