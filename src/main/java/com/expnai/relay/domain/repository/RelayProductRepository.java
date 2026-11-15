package com.expnai.relay.domain.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.expnai.relay.common.persistence.BaseRepository;
import com.expnai.relay.domain.entity.RelayProduct;

@Repository
public interface RelayProductRepository extends BaseRepository<RelayProduct> {

	List<RelayProduct> findByUserIdOrderByCreatedAtDesc(Long userId);

	Optional<RelayProduct> findByIdAndUserId(Long id, Long userId);
}
