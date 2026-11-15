package com.expnai.relay.domain.repository;

import org.springframework.stereotype.Repository;

import com.expnai.relay.common.persistence.BaseRepository;
import com.expnai.relay.domain.entity.RelayServiceConnection;

@Repository
public interface RelayServiceConnectionRepository extends BaseRepository<RelayServiceConnection> {
}
