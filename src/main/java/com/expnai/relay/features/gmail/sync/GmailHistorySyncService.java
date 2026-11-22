package com.expnai.relay.features.gmail.sync;

import java.net.InetAddress;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;

import com.expnai.relay.domain.entity.RelayGmailMailbox;
import com.expnai.relay.domain.entity.RelayGmailProcessedMessage;
import com.expnai.relay.domain.entity.RelayOutreachMessage;
import com.expnai.relay.domain.enums.RelayGmailMailboxStatus;
import com.expnai.relay.domain.repository.RelayGmailMailboxRepository;
import com.expnai.relay.domain.repository.RelayGmailProcessedMessageRepository;
import com.expnai.relay.domain.repository.RelayOutreachMessageRepository;
import com.expnai.relay.features.gmail.client.GmailApiClient;
import com.expnai.relay.features.gmail.client.GmailApiException;
import com.expnai.relay.features.gmail.client.GmailHistoryPage;
import com.expnai.relay.features.gmail.client.GmailMessageView;
import com.expnai.relay.features.gmail.config.GmailProperties;
import com.expnai.relay.features.gmail.oauth.GmailReauthRequiredException;
import com.expnai.relay.features.gmail.reply.GmailReplyProcessor;
import com.expnai.relay.features.gmail.service.GmailTokenService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class GmailHistorySyncService {

	private static final Logger log = LoggerFactory.getLogger(GmailHistorySyncService.class);

	private final GmailProperties properties;
	private final RelayGmailMailboxRepository mailboxRepository;
	private final RelayGmailProcessedMessageRepository processedMessageRepository;
	private final RelayOutreachMessageRepository outreachMessageRepository;
	private final GmailApiClient gmailApiClient;
	private final GmailTokenService tokenService;
	private final GmailReplyProcessor replyProcessor;
	private final PlatformTransactionManager transactionManager;

	private String instanceId;
	private TransactionTemplate transactionTemplate;

	@PostConstruct
	void initInstanceId() {
		this.instanceId = resolveInstanceId();
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	public void syncDueMailboxes() {
		Instant now = Instant.now();
		List<RelayGmailMailbox> due = mailboxRepository.findConnectedDueForSync(now);
		for (RelayGmailMailbox mailbox : due) {
			syncMailboxSafe(mailbox.getId());
		}
	}

	public void syncMailboxSafe(Long mailboxId) {
		transactionTemplate.executeWithoutResult(status -> doSyncMailbox(mailboxId));
	}

	private void doSyncMailbox(Long mailboxId) {
		Instant now = Instant.now();
		Instant until = now.plusSeconds(Math.max(30, properties.getSyncLeaseSeconds()));
		int acquired = mailboxRepository.tryAcquireSyncLease(mailboxId, instanceId, until, now);
		if (acquired == 0) {
			return;
		}
		try {
			RelayGmailMailbox mailbox = mailboxRepository.findById(mailboxId).orElse(null);
			if (mailbox == null || mailbox.getStatus() != RelayGmailMailboxStatus.CONNECTED) {
				return;
			}
			try {
				String accessToken = tokenService.requireAccessToken(mailbox);
				if (!StringUtils.hasText(mailbox.getHistoryId())) {
					baselineHistory(mailbox, accessToken);
					return;
				}
				try {
					syncHistory(mailbox, accessToken);
				}
				catch (GmailApiException ex) {
					if (ex.isHistoryExpired()) {
						log.warn("Gmail history cursor expired for mailboxId={}; recovering via thread sync", mailboxId);
						recoverExpiredHistory(mailbox, accessToken);
					}
					else if (ex.isAuthRevoked()) {
						markReauth(mailbox, "Gmail authorization revoked");
					}
					else {
						mailbox.setLastSyncError(ex.getMessage());
						mailboxRepository.save(mailbox);
						throw ex;
					}
				}
			}
			catch (GmailReauthRequiredException ex) {
				markReauth(mailbox, ex.getMessage());
			}
			catch (RuntimeException ex) {
				mailbox.setLastSyncError(ex.getClass().getSimpleName());
				mailboxRepository.save(mailbox);
				throw ex;
			}
		}
		finally {
			mailboxRepository.releaseSyncLease(mailboxId, instanceId);
		}
	}

	private void baselineHistory(RelayGmailMailbox mailbox, String accessToken) {
		var profile = gmailApiClient.getProfile(accessToken);
		mailbox.setHistoryId(profile.historyId());
		mailbox.setLastSyncAt(Instant.now());
		mailbox.setLastSyncError(null);
		mailboxRepository.save(mailbox);
		log.info("Baselined Gmail historyId for mailboxId={} emailDomain={}",
				mailbox.getId(), domainOf(mailbox.getEmail()));
	}

	private void syncHistory(RelayGmailMailbox mailbox, String accessToken) {
		String cursor = mailbox.getHistoryId();
		String pageToken = null;
		String latestHistoryId = cursor;
		int pages = 0;
		Set<String> seenThisRun = new HashSet<>();

		do {
			GmailHistoryPage page = gmailApiClient.listHistory(accessToken, cursor, pageToken);
			for (String messageId : page.addedMessageIds()) {
				if (!seenThisRun.add(messageId)) {
					continue;
				}
				processMessageIdempotent(mailbox, accessToken, messageId, page.latestHistoryId());
			}
			if (StringUtils.hasText(page.latestHistoryId())) {
				latestHistoryId = page.latestHistoryId();
			}
			pageToken = page.nextPageToken();
			pages++;
		}
		while (StringUtils.hasText(pageToken) && pages < properties.getHistoryMaxPages());

		// Persist history cursor only after all fetched changes were processed.
		mailbox.setHistoryId(latestHistoryId);
		mailbox.setLastSyncAt(Instant.now());
		mailbox.setLastSyncError(null);
		mailboxRepository.save(mailbox);
	}

	private void recoverExpiredHistory(RelayGmailMailbox mailbox, String accessToken) {
		List<RelayOutreachMessage> active = outreachMessageRepository.findActiveThreadsForMailbox(mailbox.getId());
		Set<String> threadIds = new HashSet<>();
		for (RelayOutreachMessage m : active) {
			if (StringUtils.hasText(m.getGmailThreadId())) {
				threadIds.add(m.getGmailThreadId());
			}
		}
		for (String threadId : threadIds) {
			List<GmailMessageView> messages = gmailApiClient.listThreadMessages(accessToken, threadId);
			for (GmailMessageView message : messages) {
				processMessageIdempotent(mailbox, accessToken, message.id(), message.historyId());
			}
		}
		var profile = gmailApiClient.getProfile(accessToken);
		mailbox.setHistoryId(profile.historyId());
		mailbox.setLastSyncAt(Instant.now());
		mailbox.setLastSyncError(null);
		mailboxRepository.save(mailbox);
	}

	private void processMessageIdempotent(
			RelayGmailMailbox mailbox,
			String accessToken,
			String messageId,
			String historyId) {
		if (processedMessageRepository.existsByMailboxIdAndGmailMessageId(mailbox.getId(), messageId)) {
			return;
		}
		GmailMessageView message = gmailApiClient.getMessage(accessToken, messageId);
		String outcome = replyProcessor.processIncoming(mailbox, message);
		RelayGmailProcessedMessage processed = new RelayGmailProcessedMessage();
		processed.setMailbox(mailbox);
		processed.setGmailMessageId(messageId);
		processed.setGmailThreadId(message.threadId());
		processed.setHistoryId(historyId);
		processed.setProcessedAt(Instant.now());
		processed.setOutcome(outcome);
		try {
			processedMessageRepository.saveAndFlush(processed);
		}
		catch (DataIntegrityViolationException dup) {
			// Concurrent worker already recorded this message — idempotent no-op.
		}
	}

	private void markReauth(RelayGmailMailbox mailbox, String reason) {
		mailbox.setStatus(RelayGmailMailboxStatus.REAUTH_REQUIRED);
		mailbox.setLastSyncError(reason);
		mailbox.setEncryptedAccessToken(null);
		mailbox.setAccessTokenExpiresAt(null);
		mailboxRepository.save(mailbox);
	}

	private String resolveInstanceId() {
		if (StringUtils.hasText(properties.getInstanceId())) {
			return properties.getInstanceId().trim();
		}
		try {
			return InetAddress.getLocalHost().getHostName() + "-" + UUID.randomUUID().toString().substring(0, 8);
		}
		catch (Exception ex) {
			return "relay-" + UUID.randomUUID();
		}
	}

	private static String domainOf(String email) {
		if (email == null) {
			return "";
		}
		int at = email.lastIndexOf('@');
		return at >= 0 ? email.substring(at + 1) : "";
	}
}
