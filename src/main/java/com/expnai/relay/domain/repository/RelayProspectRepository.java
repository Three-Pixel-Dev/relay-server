package com.expnai.relay.domain.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.expnai.relay.common.persistence.BaseRepository;
import com.expnai.relay.domain.entity.RelayProspect;

@Repository
public interface RelayProspectRepository extends BaseRepository<RelayProspect> {

	List<RelayProspect> findByUserIdOrderByCreatedAtDesc(Long userId);

	Optional<RelayProspect> findByIdAndUserId(Long id, Long userId);

	boolean existsByUserIdAndHandleIgnoreCase(Long userId, String handle);

	boolean existsByUserIdAndEmailIgnoreCase(Long userId, String email);
}
