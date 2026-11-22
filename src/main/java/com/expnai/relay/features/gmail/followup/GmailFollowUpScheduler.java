package com.expnai.relay.features.gmail.followup;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.expnai.relay.domain.entity.RelayOutreachMessage;
import com.expnai.relay.domain.repository.RelayOutreachMessageRepository;
import com.expnai.relay.features.gmail.service.GmailSendService;

import lombok.RequiredArgsConstructor;

@Component
@ConditionalOnProperty(prefix = "relay.gmail", name = "scheduling-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class GmailFollowUpScheduler {

	private static final Logger log = LoggerFactory.getLogger(GmailFollowUpScheduler.class);

	private final RelayOutreachMessageRepository outreachMessageRepository;
	private final GmailFollowUpService followUpService;
	private final GmailSendService gmailSendService;

	@Scheduled(fixedDelayString = "${relay.gmail.followup-interval-ms:60000}")
	@Transactional
	public void tick() {
		List<RelayOutreachMessage> due = outreachMessageRepository.findDueFollowUps(Instant.now());
		for (RelayOutreachMessage message : due) {
			try {
				if (!followUpService.canSendFollowUp(message)) {
					followUpService.cancelPendingForProspect(message.getProspect().getId(), "STATUS_BLOCKED");
					continue;
				}
				gmailSendService.sendPendingFollowUp(message);
			}
			catch (Exception ex) {
				log.warn("Follow-up send failed messageId={}: {}", message.getId(), ex.getClass().getSimpleName());
			}
		}
	}
}
