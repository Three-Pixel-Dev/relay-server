package com.expnai.relay.domain.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.expnai.relay.common.persistence.BaseRepository;
import com.expnai.relay.domain.entity.RelaySection;

@Repository
public interface RelaySectionRepository extends BaseRepository<RelaySection> {

	List<RelaySection> findByProductIdOrderBySortOrderAscIdAsc(Long productId);

	Optional<RelaySection> findByIdAndProductId(Long id, Long productId);

	long countByProductId(Long productId);

	@Modifying
	@Query(value = "DELETE FROM relay_section WHERE product_id = :productId", nativeQuery = true)
	void deleteHardByProductId(@Param("productId") Long productId);

	@Modifying
	@Query(value = "DELETE FROM relay_section WHERE id = :sectionId AND product_id = :productId", nativeQuery = true)
	void deleteHardByIdAndProductId(@Param("sectionId") Long sectionId, @Param("productId") Long productId);
}
