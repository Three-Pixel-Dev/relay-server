package com.expnai.relay.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.expnai.relay.common.persistence.BaseRepository;
import com.expnai.relay.domain.entity.RelayGmailMailbox;
import com.expnai.relay.domain.enums.RelayGmailMailboxStatus;

@Repository
public interface RelayGmailMailboxRepository extends BaseRepository<RelayGmailMailbox> {

	List<RelayGmailMailbox> findByUserIdOrderByCreatedAtAsc(Long userId);

	Optional<RelayGmailMailbox> findByIdAndUserId(Long id, Long userId);

	Optional<RelayGmailMailbox> findByUserIdAndEmailIgnoreCase(Long userId, String email);

	List<RelayGmailMailbox> findByStatus(RelayGmailMailboxStatus status);

	@Query("""
			select m from RelayGmailMailbox m
			where m.status = com.expnai.relay.domain.enums.RelayGmailMailboxStatus.CONNECTED
			  and (m.syncLeaseUntil is null or m.syncLeaseUntil < :now)
			order by m.lastSyncAt asc nulls first, m.id asc
			""")
	List<RelayGmailMailbox> findConnectedDueForSync(@Param("now") Instant now);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update RelayGmailMailbox m
			set m.syncLeaseOwner = :owner,
			    m.syncLeaseUntil = :until
			where m.id = :id
			  and m.status = com.expnai.relay.domain.enums.RelayGmailMailboxStatus.CONNECTED
			  and (m.syncLeaseUntil is null or m.syncLeaseUntil < :now)
			""")
	int tryAcquireSyncLease(
			@Param("id") Long id,
			@Param("owner") String owner,
			@Param("until") Instant until,
			@Param("now") Instant now);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update RelayGmailMailbox m
			set m.syncLeaseOwner = null,
			    m.syncLeaseUntil = null
			where m.id = :id
			  and m.syncLeaseOwner = :owner
			""")
	int releaseSyncLease(@Param("id") Long id, @Param("owner") String owner);

	@Query(value = """
			select * from relay_gmail_mailbox
			where id = :id and is_enable = true
			for update skip locked
			""", nativeQuery = true)
	Optional<RelayGmailMailbox> lockByIdForUpdate(@Param("id") Long id);
}
