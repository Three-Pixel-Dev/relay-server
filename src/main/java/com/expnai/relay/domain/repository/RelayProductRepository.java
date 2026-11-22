package com.expnai.relay.domain.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.expnai.relay.common.persistence.BaseRepository;
import com.expnai.relay.domain.entity.RelayProduct;

import jakarta.persistence.LockModeType;

@Repository
public interface RelayProductRepository extends BaseRepository<RelayProduct> {

	List<RelayProduct> findByUserIdOrderByCreatedAtDesc(Long userId);

	Optional<RelayProduct> findByIdAndUserId(Long id, Long userId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from RelayProduct p where p.id = :id and p.user.id = :userId")
	Optional<RelayProduct> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("UPDATE RelayProduct p SET p.spend = COALESCE(p.spend, 0) + :delta WHERE p.id = :productId")
	void addSpend(@Param("productId") Long productId, @Param("delta") BigDecimal delta);
}
