package com.expnai.relay.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.expnai.relay.common.persistence.BaseRepository;
import com.expnai.relay.domain.entity.RelayOutreachMessage;

@Repository
public interface RelayOutreachMessageRepository extends BaseRepository<RelayOutreachMessage> {

	List<RelayOutreachMessage> findByProspectIdOrderByStepAsc(Long prospectId);

	Optional<RelayOutreachMessage> findFirstByProspectIdOrderBySentAtDesc(Long prospectId);

	@Query("""
			select count(m) from RelayOutreachMessage m
			where m.prospect.user.id = :userId
			  and m.sentAt is not null
			  and m.sentAt >= :start
			""")
	long countSentSince(@Param("userId") Long userId, @Param("start") Instant start);
}
