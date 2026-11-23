package com.expnai.relay.features.partners.mail;

import java.time.Instant;
import java.util.List;

public interface ImapInboxReader {

	/** Inbox messages received on or after {@code since}, newest last, capped at {@code max}. */
	List<InboundMail> fetchSince(Instant since, int max);
}
