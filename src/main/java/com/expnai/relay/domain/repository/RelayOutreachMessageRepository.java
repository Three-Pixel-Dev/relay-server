package com.expnai.relay.domain.repository;

import java.time.Instant;
import java.util.Collection;
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

	Optional<RelayOutreachMessage> findByIdempotencyKey(String idempotencyKey);

	List<RelayOutreachMessage> findByMailboxIdAndGmailThreadId(Long mailboxId, String gmailThreadId);

	List<RelayOutreachMessage> findByMailboxIdAndGmailThreadIdIn(Long mailboxId, Collection<String> threadIds);

	@Query("""
			select m from RelayOutreachMessage m
			where m.mailbox.id = :mailboxId
			  and m.gmailThreadId is not null
			  and m.sendStatus = 'SENT'
			  and m.prospect.status in (
			      com.expnai.relay.domain.enums.RelayProspectStatus.SENT,
			      com.expnai.relay.domain.enums.RelayProspectStatus.REPLIED
			  )
			""")
	List<RelayOutreachMessage> findActiveThreadsForMailbox(@Param("mailboxId") Long mailboxId);

	@Query("""
			select m from RelayOutreachMessage m
			join fetch m.prospect p
			join fetch p.user
			left join fetch m.mailbox
			where m.sendStatus = 'PENDING'
			  and m.scheduledAt is not null
			  and m.scheduledAt <= :now
			  and m.cancelledAt is null
			order by m.scheduledAt asc
			""")
	List<RelayOutreachMessage> findDueFollowUps(@Param("now") Instant now);

	@Query("""
			select m from RelayOutreachMessage m
			where m.prospect.id = :prospectId
			  and m.sendStatus = 'PENDING'
			  and m.cancelledAt is null
			""")
	List<RelayOutreachMessage> findPendingFollowUpsForProspect(@Param("prospectId") Long prospectId);

	@Query("""
			select m from RelayOutreachMessage m
			join fetch m.prospect p
			join fetch p.user
			where m.sendStatus = 'SENT'
			  and m.mailbox is null
			  and m.sentAt is not null
			  and (m.providerId = 'smtp' or m.providerId like '<%')
			  and p.status = com.expnai.relay.domain.enums.RelayProspectStatus.SENT
			order by m.sentAt asc
			""")
	List<RelayOutreachMessage> findSmtpAwaitingReply();

	@Query("""
			select count(m) from RelayOutreachMessage m
			where m.prospect.user.id = :userId
			  and m.sentAt is not null
			  and m.sentAt >= :start
			""")
	long countSentSince(@Param("userId") Long userId, @Param("start") Instant start);
}
