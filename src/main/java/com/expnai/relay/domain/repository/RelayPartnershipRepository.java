package com.expnai.relay.domain.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.expnai.relay.common.persistence.BaseRepository;
import com.expnai.relay.domain.entity.RelayPartnership;

@Repository
public interface RelayPartnershipRepository extends BaseRepository<RelayPartnership> {

	Optional<RelayPartnership> findByProspectId(Long prospectId);

	Optional<RelayPartnership> findFirstByProductId(Long productId);

	List<RelayPartnership> findByProspectIdIn(Collection<Long> prospectIds);

	List<RelayPartnership> findByUserIdOrderByCreatedAtDesc(Long userId);
}
