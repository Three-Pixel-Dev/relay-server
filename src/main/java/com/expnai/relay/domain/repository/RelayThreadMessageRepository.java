package com.expnai.relay.domain.repository;

import java.util.List;

import org.springframework.stereotype.Repository;

import com.expnai.relay.common.persistence.BaseRepository;
import com.expnai.relay.domain.entity.RelayThreadMessage;

@Repository
public interface RelayThreadMessageRepository extends BaseRepository<RelayThreadMessage> {

	List<RelayThreadMessage> findByProductIdOrderByCreatedAtAscIdAsc(Long productId);
}
