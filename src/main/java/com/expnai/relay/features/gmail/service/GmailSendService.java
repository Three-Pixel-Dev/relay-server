package com.expnai.relay.features.gmail.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.expnai.relay.common.exception.BusinessRuleException;
import com.expnai.relay.domain.entity.RelayGmailMailbox;
import com.expnai.relay.domain.entity.RelayOutreachMessage;
import com.expnai.relay.domain.entity.RelayProspect;
import com.expnai.relay.domain.enums.RelayGmailMailboxStatus;
import com.expnai.relay.domain.repository.RelayOutreachMessageRepository;
import com.expnai.relay.domain.repository.RelaySuppressionRepository;
import com.expnai.relay.features.gmail.client.GmailApiClient;
import com.expnai.relay.features.gmail.client.GmailSendResult;
import com.expnai.relay.features.gmail.client.GoogleGmailApiClient;
import com.expnai.relay.features.gmail.followup.GmailFollowUpService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class GmailSendService {

	private final GmailApiClient gmailApiClient;
	private final GmailTokenService tokenService;
	private final RelayOutreachMessageRepository outreachMessageRepository;
	private final RelaySuppressionRepository suppressionRepository;
	private final GmailFollowUpService followUpService;

	@Transactional
	public RelayOutreachMessage sendInitial(
			RelayGmailMailbox mailbox,
			RelayProspect prospect,
			String subject,
			String body,
			String idempotencyKey) {
		if (mailbox.getStatus() != RelayGmailMailboxStatus.CONNECTED) {
			throw new BusinessRuleException("Gmail mailbox is not connected.");
		}
		if (StringUtils.hasText(idempotencyKey)) {
			var existing = outreachMessageRepository.findByIdempotencyKey(idempotencyKey);
			if (existing.isPresent()) {
				return existing.get();
			}
		}
		if (suppressionRepository.existsByUserIdAndEmailIgnoreCase(
				prospect.getUser().getId(), prospect.getEmail())) {
			throw new BusinessRuleException("Recipient is suppressed.");
		}

		try {
			String access = tokenService.requireAccessToken(mailbox);
			String raw = GoogleGmailApiClient.buildRfc822(
					mailbox.getEmail(),
					prospect.getEmail(),
					subject,
					body,
					null,
					null);
			GmailSendResult sent = gmailApiClient.send(access, raw);

			RelayOutreachMessage message = new RelayOutreachMessage();
			message.setProspect(prospect);
			message.setMailbox(mailbox);
			message.setStep(1);
			message.setSentAt(Instant.now());
			message.setProviderId("gmail");
			message.setSubject(subject);
			message.setBody(body);
			message.setGmailMessageId(sent.messageId());
			message.setGmailThreadId(sent.threadId());
			message.setSendStatus("SENT");
			message.setIdempotencyKey(idempotencyKey);
			return outreachMessageRepository.save(message);
		}
		catch (BusinessRuleException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new BusinessRuleException("Failed to send via Gmail API.");
		}
	}

	@Transactional
	public void scheduleFollowUps(RelayProspect prospect, RelayGmailMailbox mailbox, String f1, String f2) {
		Instant now = Instant.now();
		createPending(prospect, mailbox, 2, f1, now.plus(3, ChronoUnit.DAYS));
		createPending(prospect, mailbox, 3, f2, now.plus(7, ChronoUnit.DAYS));
	}

	@Transactional
	public void sendPendingFollowUp(RelayOutreachMessage message) {
		if (!followUpService.canSendFollowUp(message)) {
			message.setSendStatus("CANCELLED");
			message.setCancelledAt(Instant.now());
			message.setCancelReason("PRE_SEND_RECHECK");
			outreachMessageRepository.save(message);
			return;
		}
		RelayGmailMailbox mailbox = message.getMailbox();
		if (mailbox == null || mailbox.getStatus() != RelayGmailMailboxStatus.CONNECTED) {
			message.setSendStatus("FAILED");
			outreachMessageRepository.save(message);
			return;
		}
		try {
			String access = tokenService.requireAccessToken(mailbox);
			RelayProspect prospect = message.getProspect();
			String raw = GoogleGmailApiClient.buildRfc822(
					mailbox.getEmail(),
					prospect.getEmail(),
					message.getSubject() != null ? message.getSubject() : "(follow-up)",
					message.getBody(),
					message.getGmailThreadId(),
					null);
			GmailSendResult sent = gmailApiClient.send(access, raw);
			message.setGmailMessageId(sent.messageId());
			if (StringUtils.hasText(sent.threadId())) {
				message.setGmailThreadId(sent.threadId());
			}
			message.setSentAt(Instant.now());
			message.setSendStatus("SENT");
			message.setProviderId("gmail");
			outreachMessageRepository.save(message);
		}
		catch (Exception ex) {
			message.setSendStatus("FAILED");
			outreachMessageRepository.save(message);
			throw new BusinessRuleException("Follow-up Gmail send failed.");
		}
	}

	private void createPending(
			RelayProspect prospect,
			RelayGmailMailbox mailbox,
			int step,
			String body,
			Instant when) {
		String key = "fu-" + prospect.getId() + "-" + step;
		if (outreachMessageRepository.findByIdempotencyKey(key).isPresent()) {
			return;
		}
		RelayOutreachMessage pending = new RelayOutreachMessage();
		pending.setProspect(prospect);
		pending.setMailbox(mailbox);
		pending.setStep(step);
		pending.setBody(body);
		pending.setSubject(null);
		pending.setSendStatus("PENDING");
		pending.setScheduledAt(when);
		pending.setIdempotencyKey(key);
		pending.setProviderId("gmail");
		outreachMessageRepository.save(pending);
	}
}
