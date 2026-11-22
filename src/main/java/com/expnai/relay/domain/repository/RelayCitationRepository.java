package com.expnai.relay.domain.repository;

import java.util.List;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.expnai.relay.common.persistence.BaseRepository;
import com.expnai.relay.domain.entity.RelayCitation;

@Repository
public interface RelayCitationRepository extends BaseRepository<RelayCitation> {

	List<RelayCitation> findBySectionIdOrderByIdAsc(Long sectionId);

	List<RelayCitation> findBySectionIdInOrderBySectionIdAscIdAsc(List<Long> sectionIds);

	@Modifying
	@Query(value = "DELETE FROM relay_citation WHERE section_id = :sectionId", nativeQuery = true)
	void deleteHardBySectionId(@Param("sectionId") Long sectionId);

	@Modifying
	@Query(value = "DELETE FROM relay_citation WHERE section_id IN (SELECT id FROM relay_section WHERE product_id = :productId)", nativeQuery = true)
	void deleteHardByProductId(@Param("productId") Long productId);
}
