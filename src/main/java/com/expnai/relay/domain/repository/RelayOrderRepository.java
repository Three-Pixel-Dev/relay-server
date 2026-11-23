package com.expnai.relay.domain.repository;

import java.util.List;

import org.springframework.stereotype.Repository;

import com.expnai.relay.common.persistence.BaseRepository;
import com.expnai.relay.domain.entity.RelayOrder;

@Repository
public interface RelayOrderRepository extends BaseRepository<RelayOrder> {

	List<RelayOrder> findByProductIdOrderByOrderedAtDesc(Long productId);

	boolean existsBySourceAndExternalId(String source, String externalId);
}
