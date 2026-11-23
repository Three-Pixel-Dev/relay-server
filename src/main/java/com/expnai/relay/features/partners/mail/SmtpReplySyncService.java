package com.expnai.relay.features.partners.mail;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.expnai.relay.domain.entity.RelayOutreachMessage;
import com.expnai.relay.domain.entity.RelayProspect;
import com.expnai.relay.domain.enums.RelayProspectStatus;
import com.expnai.relay.domain.repository.RelayOutreachMessageRepository;
import com.expnai.relay.features.gmail.client.GmailMessageView;
import com.expnai.relay.features.gmail.reply.GmailReplyClassifier;
import com.expnai.relay.features.gmail.reply.GmailReplyProcessor;
import com.expnai.relay.features.gmail.reply.ReplyClassification;

import lombok.RequiredArgsConstructor;

/**
 * Finds creator replies for outreach sent over SMTP (no Gmail connection) by reading the same mailbox's
 * inbox over IMAP, then applies them like Gmail-synced replies: reply text, label, prospect status and
 * follow-up cancellation.
 */
@Service
@RequiredArgsConstructor
public class SmtpReplySyncService {

	private static final Logger log = LoggerFactory.getLogger(SmtpReplySyncService.class);

	private static final Duration MAX_LOOKBACK = Duration.ofDays(14);
	private static final Duration CLOCK_SKEW = Duration.ofMinutes(2);

	private final RelayOutreachMessageRepository outreachMessageRepository;
	private final RelayOutreachMailService mailService;
	private final ImapInboxReader inboxReader;
	private final GmailReplyClassifier classifier;
	private final GmailReplyProcessor replyProcessor;

	@Value("${relay.mail.reply-sync-max-messages:200}")
	private int maxMessages = 200;

	/** Returns how many inbox messages were applied as replies, bounces or unsubscribes. */
	public int sync() {
		if (!mailService.isConfigured()) {
			return 0;
		}
		List<RelayOutreachMessage> awaiting = outreachMessageRepository.findSmtpAwaitingReply();
		if (awaiting.isEmpty()) {
			return 0;
		}
		Instant earliest = awaiting.stream()
				.map(RelayOutreachMessage::getSentAt)
				.min(Instant::compareTo)
				.orElse(Instant.now());
		Instant floor = Instant.now().minus(MAX_LOOKBACK);
		Instant since = earliest.minus(CLOCK_SKEW).isBefore(floor) ? floor : earliest.minus(CLOCK_SKEW);

		List<InboundMail> inbox = inboxReader.fetchSince(since, maxMessages);
		int applied = 0;
		for (InboundMail mail : inbox) {
			Optional<RelayOutreachMessage> seed = match(mail, awaiting);
			if (seed.isEmpty()) {
				continue;
			}
			RelayProspect prospect = seed.get().getProspect();
			if (prospect.getStatus() != RelayProspectStatus.SENT) {
				continue;
			}
			ReplyClassification classification = classifier.classify(
					toView(mail),
					normalize(prospect.getEmail()),
					mailService.fromAddress(),
					true);
			String outcome = replyProcessor.applyClassification(prospect, toView(mail), classification);
			if (!"UNRELATED".equals(outcome) && !"AUTO_REPLY".equals(outcome)) {
				applied++;
				log.info("Applied SMTP mailbox message prospectId={} outcome={}", prospect.getId(), outcome);
			}
		}
		return applied;
	}

	private Optional<RelayOutreachMessage> match(InboundMail mail, List<RelayOutreachMessage> awaiting) {
		String sender = GmailReplyClassifier.extractEmail(mail.from());
		if (!StringUtils.hasText(sender) || sender.equalsIgnoreCase(normalize(mailService.fromAddress()))) {
			return Optional.empty();
		}
		List<String> references = mail.references() == null ? List.of() : mail.references();
		for (RelayOutreachMessage candidate : awaiting) {
			if (StringUtils.hasText(candidate.getProviderId()) && references.contains(candidate.getProviderId())) {
				return Optional.of(candidate);
			}
		}
		RelayOutreachMessage latest = null;
		for (RelayOutreachMessage candidate : awaiting) {
			boolean sameSender = sender.equals(normalize(candidate.getProspect().getEmail()));
			boolean afterSend = candidate.getSentAt() != null
					&& !mail.receivedAt().isBefore(candidate.getSentAt().minus(CLOCK_SKEW));
			if (sameSender && afterSend && (latest == null || candidate.getSentAt().isAfter(latest.getSentAt()))) {
				latest = candidate;
			}
		}
		return Optional.ofNullable(latest);
	}

	private static GmailMessageView toView(InboundMail mail) {
		String body = mail.body() == null ? "" : mail.body();
		String snippet = body.length() > 200 ? body.substring(0, 200) : body;
		return new GmailMessageView(
				mail.messageId(),
				null,
				null,
				mail.from(),
				mail.to(),
				mail.subject(),
				snippet,
				body,
				new ArrayList<>(mail.headerNames() == null ? List.of() : mail.headerNames()),
				true);
	}

	private static String normalize(String email) {
		return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
	}
}
