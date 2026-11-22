package com.expnai.relay.features.gmail.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Polls Gmail History API on a fixed delay. No Gmail Watch / Pub/Sub.
 */
@Component
@ConditionalOnProperty(prefix = "relay.gmail", name = "scheduling-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class GmailHistorySyncScheduler {

	private static final Logger log = LoggerFactory.getLogger(GmailHistorySyncScheduler.class);

	private final GmailHistorySyncService syncService;

	@Scheduled(fixedDelayString = "${relay.gmail.sync-interval-ms:60000}")
	public void tick() {
		try {
			syncService.syncDueMailboxes();
		}
		catch (Exception ex) {
			log.warn("Gmail history sync tick failed: {}", ex.getClass().getSimpleName());
		}
	}
}
