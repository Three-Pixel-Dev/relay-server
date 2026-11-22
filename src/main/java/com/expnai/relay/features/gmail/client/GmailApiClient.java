package com.expnai.relay.features.gmail.client;

import java.util.List;

/**
 * Thin Gmail API facade. Implementations must not log tokens.
 */
public interface GmailApiClient {

	record Profile(String email, String historyId) {
	}

	Profile getProfile(String accessToken);

	GmailHistoryPage listHistory(String accessToken, String startHistoryId, String pageToken);

	GmailMessageView getMessage(String accessToken, String messageId);

	List<GmailMessageView> listThreadMessages(String accessToken, String threadId);

	GmailSendResult send(String accessToken, String rawRfc822Mime);
}
