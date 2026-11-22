package com.expnai.relay.domain.repository;

import org.springframework.stereotype.Repository;

import com.expnai.relay.common.persistence.BaseRepository;
import com.expnai.relay.domain.entity.RelayGmailProcessedMessage;

@Repository
public interface RelayGmailProcessedMessageRepository extends BaseRepository<RelayGmailProcessedMessage> {

	boolean existsByMailboxIdAndGmailMessageId(Long mailboxId, String gmailMessageId);
}
