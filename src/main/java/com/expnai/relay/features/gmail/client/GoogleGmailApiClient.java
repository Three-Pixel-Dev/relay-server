package com.expnai.relay.features.gmail.client;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.expnai.relay.features.gmail.config.GmailProperties;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.History;
import com.google.api.services.gmail.model.HistoryMessageAdded;
import com.google.api.services.gmail.model.ListHistoryResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class GoogleGmailApiClient implements GmailApiClient {

	private static final Logger log = LoggerFactory.getLogger(GoogleGmailApiClient.class);

	private final GmailProperties properties;

	@Override
	public Profile getProfile(String accessToken) {
		return withRetry(() -> {
			var profile = gmail(accessToken).users().getProfile("me").execute();
			return new Profile(profile.getEmailAddress(), String.valueOf(profile.getHistoryId()));
		});
	}

	@Override
	public GmailHistoryPage listHistory(String accessToken, String startHistoryId, String pageToken) {
		return withRetry(() -> {
			var req = gmail(accessToken).users().history().list("me")
					.setStartHistoryId(new BigInteger(startHistoryId))
					.setHistoryTypes(List.of("messageAdded"));
			if (pageToken != null && !pageToken.isBlank()) {
				req.setPageToken(pageToken);
			}
			ListHistoryResponse resp;
			try {
				resp = req.execute();
			}
			catch (GoogleJsonResponseException ex) {
				throw mapException(ex);
			}
			List<String> added = new ArrayList<>();
			if (resp.getHistory() != null) {
				for (History h : resp.getHistory()) {
					if (h.getMessagesAdded() == null) {
						continue;
					}
					for (HistoryMessageAdded addedMsg : h.getMessagesAdded()) {
						if (addedMsg.getMessage() != null && addedMsg.getMessage().getId() != null) {
							added.add(addedMsg.getMessage().getId());
						}
					}
				}
			}
			String latest = resp.getHistoryId() != null ? String.valueOf(resp.getHistoryId()) : startHistoryId;
			return new GmailHistoryPage(added, resp.getNextPageToken(), latest);
		});
	}

	@Override
	public GmailMessageView getMessage(String accessToken, String messageId) {
		return withRetry(() -> {
			Message message = gmail(accessToken).users().messages().get("me", messageId).setFormat("full").execute();
			return toView(message);
		});
	}

	@Override
	public List<GmailMessageView> listThreadMessages(String accessToken, String threadId) {
		return withRetry(() -> {
			com.google.api.services.gmail.model.Thread thread =
					gmail(accessToken).users().threads().get("me", threadId).setFormat("full").execute();
			if (thread.getMessages() == null) {
				return List.of();
			}
			List<GmailMessageView> out = new ArrayList<>();
			for (Message message : thread.getMessages()) {
				out.add(toView(message));
			}
			return out;
		});
	}

	@Override
	public GmailSendResult send(String accessToken, String rawRfc822Mime) {
		return withRetry(() -> {
			Message message = new Message();
			message.setRaw(Base64.getUrlEncoder().withoutPadding()
					.encodeToString(rawRfc822Mime.getBytes(StandardCharsets.UTF_8)));
			Message sent = gmail(accessToken).users().messages().send("me", message).execute();
			return new GmailSendResult(sent.getId(), sent.getThreadId());
		});
	}

	public static String buildRfc822(String from, String to, String subject, String body, String threadId, String inReplyTo)
			throws Exception {
		Properties props = new Properties();
		Session session = Session.getInstance(props);
		MimeMessage mime = new MimeMessage(session);
		mime.setFrom(from);
		mime.setRecipients(jakarta.mail.Message.RecipientType.TO, to);
		mime.setSubject(subject, StandardCharsets.UTF_8.name());
		mime.setText(body, StandardCharsets.UTF_8.name());
		if (inReplyTo != null && !inReplyTo.isBlank()) {
			mime.setHeader("In-Reply-To", inReplyTo);
			mime.setHeader("References", inReplyTo);
		}
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		mime.writeTo(buffer);
		return buffer.toString(StandardCharsets.UTF_8);
	}

	private Gmail gmail(String accessToken) throws Exception {
		GoogleCredentials credentials = GoogleCredentials.create(new AccessToken(accessToken, null));
		HttpRequestInitializer initializer = new HttpCredentialsAdapter(credentials);
		return new Gmail.Builder(
				GoogleNetHttpTransport.newTrustedTransport(),
				GsonFactory.getDefaultInstance(),
				initializer)
				.setApplicationName("relay")
				.build();
	}

	private GmailMessageView toView(Message message) {
		List<MessagePartHeader> headers = message.getPayload() != null ? message.getPayload().getHeaders() : null;
		String from = header(headers, "From");
		String to = header(headers, "To");
		String subject = header(headers, "Subject");
		List<String> names = new ArrayList<>();
		if (headers != null) {
			for (MessagePartHeader h : headers) {
				if (h.getName() != null) {
					names.add(h.getName());
				}
			}
		}
		boolean unread = message.getLabelIds() != null && message.getLabelIds().contains("UNREAD");
		String body = extractText(message.getPayload());
		return new GmailMessageView(
				message.getId(),
				message.getThreadId(),
				message.getHistoryId() != null ? String.valueOf(message.getHistoryId()) : null,
				from,
				to,
				subject,
				message.getSnippet(),
				body,
				Collections.unmodifiableList(names),
				unread);
	}

	private static String header(List<MessagePartHeader> headers, String name) {
		if (headers == null) {
			return "";
		}
		for (MessagePartHeader h : headers) {
			if (name.equalsIgnoreCase(h.getName())) {
				return h.getValue() != null ? h.getValue() : "";
			}
		}
		return "";
	}

	private static String extractText(MessagePart part) {
		if (part == null) {
			return "";
		}
		if (part.getBody() != null && part.getBody().getData() != null
				&& "text/plain".equalsIgnoreCase(part.getMimeType())) {
			return new String(Base64.getUrlDecoder().decode(part.getBody().getData()), StandardCharsets.UTF_8);
		}
		if (part.getParts() != null) {
			for (MessagePart child : part.getParts()) {
				String text = extractText(child);
				if (!text.isBlank()) {
					return text;
				}
			}
		}
		if (part.getBody() != null && part.getBody().getData() != null) {
			return new String(Base64.getUrlDecoder().decode(part.getBody().getData()), StandardCharsets.UTF_8);
		}
		return "";
	}

	<T> T withRetry(ApiCall<T> call) {
		long backoff = properties.getRateLimitInitialBackoffMs();
		int attempts = Math.max(1, properties.getRateLimitMaxAttempts());
		GmailApiException last = null;
		for (int i = 0; i < attempts; i++) {
			try {
				return call.execute();
			}
			catch (GmailApiException ex) {
				last = ex;
				if (!ex.isRateLimited() || i == attempts - 1) {
					throw ex;
				}
				sleep(backoff);
				backoff = Math.min(properties.getRateLimitMaxBackoffMs(), backoff * 2);
			}
			catch (Exception ex) {
				if (ex instanceof GoogleJsonResponseException gj) {
					throw mapException(gj);
				}
				throw new GmailApiException("Gmail API call failed", 0, false, false, false, ex);
			}
		}
		throw last != null ? last : new GmailApiException("Gmail API retries exhausted", 0, false, true, false, null);
	}

	private GmailApiException mapException(GoogleJsonResponseException ex) {
		int status = ex.getStatusCode();
		boolean historyExpired = status == 404;
		boolean rateLimited = status == 429 || status == 403 && messageContains(ex, "rateLimitExceeded", "userRateLimitExceeded");
		boolean authRevoked = status == 401 || status == 403 && messageContains(ex, "invalid_grant", "authError", "insufficientPermissions");
		log.warn("Gmail API error status={} historyExpired={} rateLimited={} authRevoked={}",
				status, historyExpired, rateLimited, authRevoked);
		return new GmailApiException(ex.getMessage(), status, historyExpired, rateLimited, authRevoked, ex);
	}

	private static boolean messageContains(GoogleJsonResponseException ex, String... needles) {
		String hay = String.valueOf(ex.getDetails() != null ? ex.getDetails() : ex.getMessage()).toLowerCase();
		for (String n : needles) {
			if (hay.contains(n.toLowerCase())) {
				return true;
			}
		}
		return false;
	}

	void sleep(long ms) {
		try {
			java.lang.Thread.sleep(ms);
		}
		catch (InterruptedException ie) {
			java.lang.Thread.currentThread().interrupt();
		}
	}

	@FunctionalInterface
	interface ApiCall<T> {
		T execute() throws Exception;
	}
}
