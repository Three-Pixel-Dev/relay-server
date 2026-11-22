package com.expnai.relay.domain.repository;

import java.util.List;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.expnai.relay.common.persistence.BaseRepository;
import com.expnai.relay.domain.entity.RelayAiUsageEvent;

@Repository
public interface RelayAiUsageEventRepository extends BaseRepository<RelayAiUsageEvent> {

	List<RelayAiUsageEvent> findByProductIdOrderByCreatedAtAsc(Long productId);

	@Modifying
	@Query(value = "DELETE FROM relay_ai_usage_event WHERE product_id = :productId", nativeQuery = true)
	void deleteHardByProductId(@Param("productId") Long productId);
}

