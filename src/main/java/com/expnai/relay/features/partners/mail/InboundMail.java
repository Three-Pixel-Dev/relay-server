package com.expnai.relay.features.partners.mail;

import java.time.Instant;
import java.util.List;

/** One message read from the outreach mailbox inbox over IMAP. */
public record InboundMail(
		String messageId,
		String from,
		String to,
		String subject,
		String body,
		List<String> references,
		List<String> headerNames,
		Instant receivedAt
) {
}
