package com.expnai.relay.features.gmail.reply;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.expnai.relay.domain.entity.RelayGmailMailbox;
import com.expnai.relay.domain.entity.RelayOutreachMessage;
import com.expnai.relay.domain.entity.RelayProspect;
import com.expnai.relay.domain.entity.RelayReply;
import com.expnai.relay.domain.entity.RelaySuppression;
import com.expnai.relay.domain.enums.RelayProspectStatus;
import com.expnai.relay.domain.enums.RelayReplyLabel;
import com.expnai.relay.domain.repository.RelayOutreachMessageRepository;
import com.expnai.relay.domain.repository.RelayProspectRepository;
import com.expnai.relay.domain.repository.RelayReplyRepository;
import com.expnai.relay.domain.repository.RelaySuppressionRepository;
import com.expnai.relay.features.gmail.client.GmailMessageView;
import com.expnai.relay.features.gmail.followup.GmailFollowUpService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class GmailReplyProcessor {

	private static final Logger log = LoggerFactory.getLogger(GmailReplyProcessor.class);

	private final RelayOutreachMessageRepository outreachMessageRepository;
	private final RelayProspectRepository prospectRepository;
	private final RelayReplyRepository replyRepository;
	private final RelaySuppressionRepository suppressionRepository;
	private final GmailReplyClassifier classifier;
	private final GmailFollowUpService followUpService;

	@Transactional
	public String processIncoming(RelayGmailMailbox mailbox, GmailMessageView message) {
		List<RelayOutreachMessage> threadMessages = outreachMessageRepository
				.findByMailboxIdAndGmailThreadId(mailbox.getId(), message.threadId());
		if (threadMessages.isEmpty()) {
			return "UNRELATED_THREAD";
		}

		RelayOutreachMessage seed = threadMessages.get(0);
		RelayProspect prospect = seed.getProspect();
		String prospectEmail = prospect.getEmail() != null
				? prospect.getEmail().trim().toLowerCase(Locale.ROOT)
				: "";

		ReplyClassification classification = classifier.classify(
				message,
				prospectEmail,
				mailbox.getEmail(),
				true);

		return switch (classification) {
			case UNRELATED, AUTO_REPLY -> classification.name();
			case BOUNCE, UNSUBSCRIBE -> {
				suppress(mailbox, prospectEmail, classification.name());
				followUpService.cancelPendingForProspect(prospect.getId(), classification.name());
				prospect.setStatus(RelayProspectStatus.DECLINED);
				prospectRepository.save(prospect);
				yield classification.name();
			}
			case HUMAN_INTERESTED, HUMAN_DECLINED, HUMAN_OTHER -> {
				saveReply(prospect, message, classification);
				followUpService.cancelPendingForProspect(prospect.getId(), "HUMAN_REPLY");
				if (classification == ReplyClassification.HUMAN_INTERESTED) {
					prospect.setStatus(RelayProspectStatus.INTERESTED);
				}
				else if (classification == ReplyClassification.HUMAN_DECLINED) {
					prospect.setStatus(RelayProspectStatus.DECLINED);
				}
				else {
					prospect.setStatus(RelayProspectStatus.REPLIED);
				}
				prospectRepository.save(prospect);
				log.info("Processed human reply mailboxId={} prospectId={} classification={}",
						mailbox.getId(), prospect.getId(), classification);
				yield classification.name();
			}
		};
	}

	private void saveReply(RelayProspect prospect, GmailMessageView message, ReplyClassification classification) {
		RelayReply reply = new RelayReply();
		reply.setProspect(prospect);
		reply.setText(GmailReplyClassifier.stripQuoted(
				message.bodyText() != null && !message.bodyText().isBlank()
						? message.bodyText()
						: message.snippet()));
		reply.setLabel(switch (classification) {
			case HUMAN_INTERESTED -> RelayReplyLabel.INTERESTED;
			case HUMAN_DECLINED -> RelayReplyLabel.DECLINED;
			default -> RelayReplyLabel.OTHER;
		});
		reply.setCorrected(false);
		replyRepository.save(reply);
	}

	private void suppress(RelayGmailMailbox mailbox, String email, String reason) {
		if (email == null || email.isBlank()) {
			return;
		}
		if (suppressionRepository.existsByUserIdAndEmailIgnoreCase(mailbox.getUser().getId(), email)) {
			return;
		}
		RelaySuppression suppression = new RelaySuppression();
		suppression.setUser(mailbox.getUser());
		suppression.setEmail(email);
		suppression.setReason(reason + " at " + Instant.now());
		suppressionRepository.save(suppression);
	}
}
