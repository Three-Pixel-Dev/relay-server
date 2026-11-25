package com.expnai.relay.domain.repository;

import java.util.List;

import org.springframework.stereotype.Repository;

import com.expnai.relay.common.persistence.BaseRepository;
import com.expnai.relay.domain.entity.RelayMetric;

@Repository
public interface RelayMetricRepository extends BaseRepository<RelayMetric> {

	List<RelayMetric> findByProductId(Long productId);
}
