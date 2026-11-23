package com.expnai.relay.features.partners.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Polls the SMTP mailbox inbox for creator replies. Skipped when Gmail OAuth mailboxes carry the outreach. */
@Component
@ConditionalOnProperty(prefix = "relay.mail", name = "reply-sync-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class SmtpReplySyncScheduler {

	private static final Logger log = LoggerFactory.getLogger(SmtpReplySyncScheduler.class);

	private final SmtpReplySyncService syncService;

	@Scheduled(
			initialDelayString = "${relay.mail.reply-sync-initial-delay-ms:30000}",
			fixedDelayString = "${relay.mail.reply-sync-interval-ms:60000}")
	public void tick() {
		try {
			syncService.sync();
		}
		catch (Exception ex) {
			log.warn("SMTP reply sync failed: {}", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
		}
	}
}
