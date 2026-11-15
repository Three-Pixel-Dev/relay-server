package com.expnai.relay.domain.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.expnai.relay.common.persistence.BaseRepository;
import com.expnai.relay.domain.entity.RelaySource;

@Repository
public interface RelaySourceRepository extends BaseRepository<RelaySource> {

	List<RelaySource> findByProductIdOrderByCreatedAtAsc(Long productId);

	Optional<RelaySource> findByIdAndProductId(Long id, Long productId);
}
