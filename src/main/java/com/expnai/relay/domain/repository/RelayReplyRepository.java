package com.expnai.relay.domain.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.expnai.relay.common.persistence.BaseRepository;
import com.expnai.relay.domain.entity.RelayReply;

@Repository
public interface RelayReplyRepository extends BaseRepository<RelayReply> {

	Optional<RelayReply> findFirstByProspectIdOrderByCreatedAtDesc(Long prospectId);

	List<RelayReply> findByProspectIdIn(Collection<Long> prospectIds);
}
