package com.expnai.relay.domain.repository;

import org.springframework.stereotype.Repository;

import com.expnai.relay.common.persistence.BaseRepository;
import com.expnai.relay.domain.entity.RelayListing;

@Repository
public interface RelayListingRepository extends BaseRepository<RelayListing> {
}
