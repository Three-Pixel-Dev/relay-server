package com.expnai.relay.features.gmail.reply;

import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.expnai.relay.features.gmail.client.GmailMessageView;

@Component
public class GmailReplyClassifier {

	private static final Pattern EMAIL = Pattern.compile("(?i)<([^>]+)>|([\\w.+%-]+@[\\w.-]+\\.[A-Za-z]{2,})");
	private static final Pattern INTERESTED = Pattern.compile(
			"(?i)\\b(yes|interested|send (it|the outline)|sounds good|let'?s do it|i'?m in)\\b");
	private static final Pattern DECLINED = Pattern.compile(
			"(?i)\\b(no thanks|not interested|not for me|pass|unsubscribe)\\b");

	/**
	 * Classifies a candidate reply. Requires thread match and prospect-as-sender / mailbox-as-recipient.
	 */
	public ReplyClassification classify(
			GmailMessageView message,
			String prospectEmail,
			String mailboxEmail,
			boolean threadMatchesOutreach) {
		if (!threadMatchesOutreach) {
			return ReplyClassification.UNRELATED;
		}
		if (isBounce(message)) {
			return ReplyClassification.BOUNCE;
		}
		if (isAutoReply(message)) {
			return ReplyClassification.AUTO_REPLY;
		}

		String from = extractEmail(message.from());
		if (!StringUtils.hasText(from) || !from.equalsIgnoreCase(prospectEmail)) {
			return ReplyClassification.UNRELATED;
		}
		if (StringUtils.hasText(mailboxEmail)
				&& StringUtils.hasText(message.to())
				&& !message.to().toLowerCase(Locale.ROOT).contains(mailboxEmail.toLowerCase(Locale.ROOT))) {
			return ReplyClassification.UNRELATED;
		}
		if (isUnsubscribe(message)) {
			return ReplyClassification.UNSUBSCRIBE;
		}

		String body = stripQuoted(message.bodyText() != null ? message.bodyText() : message.snippet());
		if (INTERESTED.matcher(body).find()) {
			return ReplyClassification.HUMAN_INTERESTED;
		}
		if (DECLINED.matcher(body).find()) {
			return ReplyClassification.HUMAN_DECLINED;
		}
		return ReplyClassification.HUMAN_OTHER;
	}

	public static String extractEmail(String headerValue) {
		if (!StringUtils.hasText(headerValue)) {
			return "";
		}
		var matcher = EMAIL.matcher(headerValue);
		if (matcher.find()) {
			String a = matcher.group(1);
			String b = matcher.group(2);
			return (a != null ? a : b).trim().toLowerCase(Locale.ROOT);
		}
		return headerValue.trim().toLowerCase(Locale.ROOT);
	}

	public static String stripQuoted(String body) {
		if (body == null) {
			return "";
		}
		StringBuilder out = new StringBuilder();
		for (String line : body.split("\\R")) {
			String trimmed = line.trim();
			if (trimmed.startsWith(">")) {
				break;
			}
			if (trimmed.matches("(?i)^on .+ wrote:$") || trimmed.startsWith("---------- Forwarded message")) {
				break;
			}
			out.append(line).append('\n');
		}
		return out.toString().trim();
	}

	private static boolean isAutoReply(GmailMessageView message) {
		String subject = message.subject() != null ? message.subject().toLowerCase(Locale.ROOT) : "";
		if (subject.contains("out of office") || subject.contains("automatic reply") || subject.startsWith("auto:")) {
			return true;
		}
		for (String name : message.headerNames()) {
			String n = name.toLowerCase(Locale.ROOT);
			if (n.equals("auto-submitted") || n.equals("x-autoreply") || n.equals("x-autorespond")) {
				return true;
			}
		}
		return false;
	}

	private static boolean isBounce(GmailMessageView message) {
		String from = message.from() != null ? message.from().toLowerCase(Locale.ROOT) : "";
		String subject = message.subject() != null ? message.subject().toLowerCase(Locale.ROOT) : "";
		return from.contains("mailer-daemon") || from.contains("postmaster")
				|| subject.contains("delivery status") || subject.contains("undeliverable")
				|| subject.contains("returned mail");
	}

	private static boolean isUnsubscribe(GmailMessageView message) {
		String body = (message.bodyText() != null ? message.bodyText() : "")
				+ " "
				+ (message.subject() != null ? message.subject() : "");
		String lower = body.toLowerCase(Locale.ROOT).trim();
		return lower.equals("stop") || lower.equals("unsubscribe") || lower.startsWith("unsubscribe");
	}
}
