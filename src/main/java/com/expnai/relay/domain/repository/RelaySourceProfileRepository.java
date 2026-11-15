package com.expnai.relay.domain.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.expnai.relay.common.persistence.BaseRepository;
import com.expnai.relay.domain.entity.RelaySourceProfile;

@Repository
public interface RelaySourceProfileRepository extends BaseRepository<RelaySourceProfile> {

	Optional<RelaySourceProfile> findByProductId(Long productId);

	@Modifying
	@Query(value = "DELETE FROM relay_source_profile WHERE product_id = :productId", nativeQuery = true)
	void deleteHardByProductId(@Param("productId") Long productId);
}
