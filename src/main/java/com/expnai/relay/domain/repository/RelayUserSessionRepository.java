package com.expnai.relay.domain.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.expnai.relay.common.persistence.BaseRepository;
import com.expnai.relay.domain.entity.RelayUserSession;

@Repository
public interface RelayUserSessionRepository extends BaseRepository<RelayUserSession> {

	Optional<RelayUserSession> findByIdAndUserIdAndRevokedAtIsNull(Long id, Long userId);

	List<RelayUserSession> findByUserIdAndRevokedAtIsNullOrderByLastActiveAtDesc(Long userId);
}
