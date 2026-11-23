package com.expnai.relay.features.partners.scout;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * Reads what Instagram still exposes on a public profile page without a login: Open Graph tags and
 * the biography string embedded in the page JSON. Full feeds stay behind the login wall; this is
 * enough to recover a business email and follower count for Partners scout.
 */
@Component
public class InstagramProfilePageParser {

	private static final Pattern HANDLE_IN_TITLE = Pattern.compile("@([A-Za-z0-9._]{1,30})");
	private static final Pattern FOLLOWERS = Pattern.compile(
			"([\\d][\\d.,]*\\s?[kKmM]?)\\s+Followers", Pattern.CASE_INSENSITIVE);
	private static final Pattern BIOGRAPHY_JSON = Pattern.compile(
			"\"biography\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
	private static final Pattern NAME_BEFORE_HANDLE = Pattern.compile("^(.*?)\\s*\\(@[^)]+\\)");

	public Optional<InstagramProfile> parse(String html, String expectedHandle) {
		if (html == null || html.isBlank()) {
			return Optional.empty();
		}
		Document document = Jsoup.parse(html);
		String title = meta(document, "og:title");
		if (title.isBlank()) {
			title = document.title();
		}
		String description = meta(document, "og:description");
		if (description.isBlank()) {
			description = meta(document, "description");
		}

		String handle = expectedHandle == null ? "" : expectedHandle.replace("@", "").trim();
		if (handle.isBlank()) {
			Matcher handleMatcher = HANDLE_IN_TITLE.matcher(title);
			if (handleMatcher.find()) {
				handle = handleMatcher.group(1);
			}
		}
		if (handle.isBlank() || !handle.matches("[A-Za-z0-9._]{1,30}")) {
			return Optional.empty();
		}

		String followers = "";
		Matcher followersMatcher = FOLLOWERS.matcher(description);
		if (followersMatcher.find()) {
			followers = InstagramProfileParser.compactCount(followersMatcher.group(1));
		}

		String bio = decodeJsonString(firstBiography(html));
		if (bio.isBlank() && !description.isBlank() && !FOLLOWERS.matcher(description).find()) {
			bio = description.trim();
		}

		String name = displayName(title, handle);
		return Optional.of(new InstagramProfile("@" + handle, name, followers, bio));
	}

	private static String meta(Document document, String property) {
		Element byProperty = document.selectFirst("meta[property=" + property + "]");
		if (byProperty != null && !byProperty.attr("content").isBlank()) {
			return byProperty.attr("content").trim();
		}
		Element byName = document.selectFirst("meta[name=" + property + "]");
		return byName == null ? "" : byName.attr("content").trim();
	}

	private static String firstBiography(String html) {
		Matcher matcher = BIOGRAPHY_JSON.matcher(html);
		return matcher.find() ? matcher.group(1) : "";
	}

	private static String decodeJsonString(String raw) {
		if (raw == null || raw.isBlank()) {
			return "";
		}
		return raw
				.replace("\\n", "\n")
				.replace("\\r", "\r")
				.replace("\\t", "\t")
				.replace("\\\"", "\"")
				.replace("\\/", "/")
				.replace("\\\\", "\\")
				.trim();
	}

	private static String displayName(String title, String handle) {
		if (title != null && !title.isBlank()) {
			Matcher matcher = NAME_BEFORE_HANDLE.matcher(title.trim());
			if (matcher.find()) {
				String name = matcher.group(1).trim();
				if (!name.isBlank() && !name.equalsIgnoreCase(handle)) {
					return name;
				}
			}
		}
		return handle;
	}

	static boolean looksLikeProfilePage(String html) {
		if (html == null || html.isBlank()) {
			return false;
		}
		String lower = html.toLowerCase(Locale.ROOT);
		return lower.contains("instagram") && (lower.contains("og:description") || lower.contains("\"biography\""));
	}
}
