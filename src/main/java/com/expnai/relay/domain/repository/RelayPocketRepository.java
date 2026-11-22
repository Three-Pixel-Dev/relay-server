package com.expnai.relay.domain.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.expnai.relay.common.persistence.BaseRepository;
import com.expnai.relay.domain.entity.RelayPocket;

@Repository
public interface RelayPocketRepository extends BaseRepository<RelayPocket> {

	List<RelayPocket> findByProductIdOrderByIdAsc(Long productId);

	Optional<RelayPocket> findByIdAndProductId(Long id, Long productId);

	@Modifying
	@Query(value = "DELETE FROM relay_pocket WHERE product_id = :productId", nativeQuery = true)
	void deleteHardByProductId(@Param("productId") Long productId);
}
