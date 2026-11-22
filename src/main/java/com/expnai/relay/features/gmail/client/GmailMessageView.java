package com.expnai.relay.features.gmail.client;

import java.util.List;

public record GmailMessageView(
		String id,
		String threadId,
		String historyId,
		String from,
		String to,
		String subject,
		String snippet,
		String bodyText,
		List<String> headerNames,
		boolean unread
) {
}
