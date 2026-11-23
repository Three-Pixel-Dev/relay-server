package com.expnai.relay.features.partners.scout;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Turns a search-engine hit for an Instagram profile into a creator. The snippet search engines show is
 * Instagram's own description: "76K Followers, 12 Following, 300 Posts - Name (@handle) on Instagram: "bio"".
 */
@Component
public class InstagramProfileParser {

	private static final Set<String> NOT_PROFILES = Set.of(
			"p", "reel", "reels", "explore", "accounts", "stories", "tv", "directory", "about", "legal", "web",
			"developer", "challenge", "s", "direct", "popular", "tags", "locations");

	private static final Pattern FOLLOWERS = Pattern.compile("([\\d][\\d.,]*\\s?[kKmM]?)\\s+Followers", Pattern.CASE_INSENSITIVE);
	private static final Pattern NAME_BEFORE_HANDLE = Pattern.compile("^(.*?)\\s*\\(@[^)]+\\)");
	private static final Pattern NAME_PREFIX = Pattern.compile("^See Instagram photos and videos from\\s+", Pattern.CASE_INSENSITIVE);
	private static final Pattern BIO = Pattern.compile("on Instagram:\\s*[\"“](.*)$", Pattern.DOTALL);

	public Optional<InstagramProfile> parse(WebSearchHit hit) {
		String handle = profileHandle(hit.url());
		if (handle == null) {
			return Optional.empty();
		}
		String snippet = hit.snippet() == null ? "" : hit.snippet();
		String followers = "";
		Matcher followersMatcher = FOLLOWERS.matcher(snippet);
		if (followersMatcher.find()) {
			followers = compactCount(followersMatcher.group(1));
		}
		String bio = "";
		Matcher bioMatcher = BIO.matcher(snippet);
		if (bioMatcher.find()) {
			bio = bioMatcher.group(1).replaceAll("[\"”]\\s*$", "").trim();
		}
		else if (!snippet.isBlank() && followers.isEmpty()) {
			bio = snippet;
		}
		return Optional.of(new InstagramProfile("@" + handle, displayName(hit.title(), snippet, handle), followers, bio));
	}

	private static String profileHandle(String url) {
		try {
			URI uri = URI.create(url.trim());
			String host = uri.getHost();
			if (host == null || !(host.equals("instagram.com") || host.endsWith(".instagram.com"))) {
				return null;
			}
			String path = uri.getPath() == null ? "" : uri.getPath();
			String[] segments = path.replaceAll("^/+", "").split("/");
			if (segments.length == 0 || segments[0].isBlank()) {
				return null;
			}
			String first = segments[0];
			if (segments.length > 1 && !segments[1].isBlank()) {
				return null;
			}
			if (NOT_PROFILES.contains(first.toLowerCase(Locale.ROOT)) || !first.matches("[A-Za-z0-9._]{1,30}")) {
				return null;
			}
			return first;
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private static String displayName(String title, String snippet, String handle) {
		for (String source : new String[] { title, snippet.replaceAll("^.*? Posts\\s*-\\s*", "") }) {
			if (source == null) {
				continue;
			}
			Matcher matcher = NAME_BEFORE_HANDLE.matcher(source.trim());
			if (matcher.find()) {
				String name = NAME_PREFIX.matcher(matcher.group(1)).replaceFirst("").trim();
				if (!name.isBlank()) {
					return name;
				}
			}
		}
		return handle;
	}

	/** "276K", "1.2m" and "2,019" are kept short and lowercase, as the Partners list shows them. */
	static String compactCount(String raw) {
		return raw.replaceAll("\\s", "").toLowerCase(Locale.ROOT);
	}
}
