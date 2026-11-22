package com.expnai.relay.domain.repository;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.expnai.relay.common.persistence.BaseRepository;
import com.expnai.relay.domain.entity.RelayOutreachTemplate;

@Repository
public interface RelayOutreachTemplateRepository extends BaseRepository<RelayOutreachTemplate> {

	Optional<RelayOutreachTemplate> findFirstByUserIdOrderByCreatedAtAsc(Long userId);
}
