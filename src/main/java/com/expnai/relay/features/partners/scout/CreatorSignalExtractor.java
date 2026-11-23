package com.expnai.relay.features.partners.scout;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/** Finds the public email, email-list mention and product hints in a creator's bio or About text. */
@Component
public class CreatorSignalExtractor {

	public static final String NOT_STATED = "not stated";
	public static final String NO_PRODUCT = "No paid product found";

	private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9\\-]+(?:\\.[A-Za-z0-9\\-]+)*\\.[A-Za-z]{2,}");
	private static final List<String> IMAGE_SUFFIXES = List.of(".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg");

	private static final Pattern LIST_MENTION = Pattern.compile(
			"newsletter|email list|mailing list|weekly email|free email|email course|beehiiv|substack|convertkit|kit\\.com"
					+ "|ck\\.page|mailchimp|mailerlite|flodesk|buttondown",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern LIST_SIZE = Pattern.compile(
			"(\\d[\\d,.]*\\s?[kKmM]?)\\+?\\s*(?:email\\s+)?(?:subscribers|readers|members)\\b", Pattern.CASE_INSENSITIVE);

	private static final Pattern PRODUCT_HOST = Pattern.compile(
			"gumroad|etsy\\.com|stan\\.store|teachable|kajabi|whop\\.com|payhip|shopify|patreon\\.com|skool\\.com|thinkific|podia"
					+ "|teacherspayteachers|tpt\\.com",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern PRODUCT_WORD = Pattern.compile(
			"\\b(shop|store|course|courses|ebook|e-book|printables?|templates?)\\b", Pattern.CASE_INSENSITIVE);

	public Optional<String> findEmail(String... texts) {
		for (String text : texts) {
			if (text == null) {
				continue;
			}
			Matcher matcher = EMAIL.matcher(text);
			while (matcher.find()) {
				String email = matcher.group().toLowerCase(Locale.ROOT);
				String stripped = email.replaceAll("^[._%+\\-]+", "");
				if (stripped.isEmpty() || IMAGE_SUFFIXES.stream().anyMatch(stripped::endsWith)) {
					continue;
				}
				return Optional.of(stripped);
			}
		}
		return Optional.empty();
	}

	/** Returns for example {@code Bio: “Free weekly email, link below”}, or empty if the text mentions no list. */
	public Optional<String> listSignal(String label, String text) {
		if (text == null || text.isBlank()) {
			return Optional.empty();
		}
		Matcher matcher = LIST_MENTION.matcher(text);
		if (!matcher.find()) {
			return Optional.empty();
		}
		int from = Math.max(0, matcher.start() - 50);
		int to = Math.min(text.length(), matcher.end() + 70);
		String excerpt = text.substring(from, to).replaceAll("\\s+", " ").trim();
		return Optional.of(label + ": “" + (from > 0 ? "…" : "") + excerpt + (to < text.length() ? "…" : "") + "”");
	}

	public String listSize(String text) {
		if (text == null || !LIST_MENTION.matcher(text).find()) {
			return NOT_STATED;
		}
		Matcher matcher = LIST_SIZE.matcher(text);
		if (!matcher.find()) {
			return NOT_STATED;
		}
		return formatCount(matcher.group(1)).orElse(NOT_STATED);
	}

	public String productNote(String text) {
		if (text == null || text.isBlank()) {
			return NO_PRODUCT;
		}
		Matcher host = PRODUCT_HOST.matcher(text);
		if (host.find()) {
			return "Sells something (" + host.group().toLowerCase(Locale.ROOT) + " link)";
		}
		Matcher word = PRODUCT_WORD.matcher(text);
		if (word.find()) {
			return "Mentions a " + word.group().toLowerCase(Locale.ROOT);
		}
		return NO_PRODUCT;
	}

	private static Optional<String> formatCount(String raw) {
		String value = raw.replaceAll("[\\s,]", "").toLowerCase(Locale.ROOT);
		double multiplier = 1;
		if (value.endsWith("k")) {
			multiplier = 1_000;
			value = value.substring(0, value.length() - 1);
		}
		else if (value.endsWith("m")) {
			multiplier = 1_000_000;
			value = value.substring(0, value.length() - 1);
		}
		try {
			long count = Math.round(Double.parseDouble(value) * multiplier);
			return count > 0 ? Optional.of(String.format(Locale.US, "%,d", count)) : Optional.empty();
		}
		catch (NumberFormatException ex) {
			return Optional.empty();
		}
	}
}
