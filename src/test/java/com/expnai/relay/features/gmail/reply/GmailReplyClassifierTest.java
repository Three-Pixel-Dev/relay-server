package com.expnai.relay.features.gmail.reply;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.expnai.relay.features.gmail.client.GmailMessageView;

class GmailReplyClassifierTest {

	private final GmailReplyClassifier classifier = new GmailReplyClassifier();

	@Test
	void unrelatedWhenThreadDoesNotMatch() {
		var msg = message("Creator <a@b.com>", "me@x.com", "hello", "yes interested", List.of());
		assertEquals(
				ReplyClassification.UNRELATED,
				classifier.classify(msg, "a@b.com", "me@x.com", false));
	}

	@Test
	void unrelatedWhenSenderIsNotProspect() {
		var msg = message("Other <z@b.com>", "me@x.com", "Re: hi", "yes", List.of());
		assertEquals(
				ReplyClassification.UNRELATED,
				classifier.classify(msg, "a@b.com", "me@x.com", true));
	}

	@Test
	void humanInterested() {
		var msg = message("A <a@b.com>", "me@x.com", "Re: hi", "Yes, send the outline please", List.of());
		assertEquals(
				ReplyClassification.HUMAN_INTERESTED,
				classifier.classify(msg, "a@b.com", "me@x.com", true));
	}

	@Test
	void autoReplyDetected() {
		var msg = message("A <a@b.com>", "me@x.com", "Out of Office", "I am away", List.of("Auto-Submitted"));
		assertEquals(
				ReplyClassification.AUTO_REPLY,
				classifier.classify(msg, "a@b.com", "me@x.com", true));
	}

	@Test
	void bounceDetected() {
		var msg = message("Mailer-Daemon <postmaster@x.com>", "me@x.com", "Undeliverable", "failed", List.of());
		assertEquals(
				ReplyClassification.BOUNCE,
				classifier.classify(msg, "a@b.com", "me@x.com", true));
	}

	@Test
	void stripQuotedRemovesHistory() {
		String stripped = GmailReplyClassifier.stripQuoted("Thanks!\n\nOn Mon wrote:\n> old");
		assertEquals("Thanks!", stripped);
	}

	private static GmailMessageView message(
			String from,
			String to,
			String subject,
			String body,
			List<String> headers) {
		return new GmailMessageView("m1", "t1", "1", from, to, subject, body, body, headers, true);
	}
}
