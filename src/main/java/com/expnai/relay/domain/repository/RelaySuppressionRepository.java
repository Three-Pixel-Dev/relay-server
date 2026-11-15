package com.expnai.relay.domain.repository;

import org.springframework.stereotype.Repository;

import com.expnai.relay.common.persistence.BaseRepository;
import com.expnai.relay.domain.entity.RelaySuppression;

@Repository
public interface RelaySuppressionRepository extends BaseRepository<RelaySuppression> {
}
