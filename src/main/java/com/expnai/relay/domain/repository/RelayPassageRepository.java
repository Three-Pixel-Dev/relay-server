package com.expnai.relay.domain.repository;

import java.util.List;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.expnai.relay.common.persistence.BaseRepository;
import com.expnai.relay.domain.entity.RelayPassage;

@Repository
public interface RelayPassageRepository extends BaseRepository<RelayPassage> {

	List<RelayPassage> findByProductIdOrderByIdAsc(Long productId);

	long countByProductId(Long productId);

	@Modifying
	@Query(value = "DELETE FROM relay_passage WHERE source_id = :sourceId AND product_id = :productId", nativeQuery = true)
	void deleteHardBySourceIdAndProductId(@Param("sourceId") Long sourceId, @Param("productId") Long productId);

	@Modifying
	@Query(value = "DELETE FROM relay_passage WHERE product_id = :productId", nativeQuery = true)
	void deleteHardByProductId(@Param("productId") Long productId);
}
