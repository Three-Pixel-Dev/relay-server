package com.expnai.relay.domain.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.expnai.relay.common.persistence.BaseRepository;
import com.expnai.relay.domain.entity.RelayProductFile;

@Repository
public interface RelayProductFileRepository extends BaseRepository<RelayProductFile> {

	List<RelayProductFile> findByProductIdOrderByIdAsc(Long productId);

	Optional<RelayProductFile> findByIdAndProductId(Long id, Long productId);

	@Modifying
	@Query(value = "DELETE FROM relay_product_file WHERE product_id = :productId", nativeQuery = true)
	void deleteHardByProductId(@Param("productId") Long productId);
}
