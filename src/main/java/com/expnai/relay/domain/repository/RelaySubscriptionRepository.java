package com.expnai.relay.domain.repository;

import org.springframework.stereotype.Repository;

import com.expnai.relay.common.persistence.BaseRepository;
import com.expnai.relay.domain.entity.RelaySubscription;

@Repository
public interface RelaySubscriptionRepository extends BaseRepository<RelaySubscription> {
}
