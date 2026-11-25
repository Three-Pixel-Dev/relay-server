package com.expnai.relay.domain.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.expnai.relay.common.persistence.BaseRepository;
import com.expnai.relay.domain.entity.RelayChannel;

@Repository
public interface RelayChannelRepository extends BaseRepository<RelayChannel> {

	List<RelayChannel> findByProductIdOrderByIdAsc(Long productId);

	Optional<RelayChannel> findByProductIdAndChannelTypeIgnoreCase(Long productId, String channelType);

	List<RelayChannel> findByProductIdAndEnabledTrueOrderByIdAsc(Long productId);
}
