package com.expnai.relay.features.product.ingest;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.expnai.relay.features.partners.scout.CreatorPageFetcher;
import com.expnai.relay.features.partners.scout.InstagramProfile;
import com.expnai.relay.features.partners.scout.InstagramProfilePageParser;
import com.expnai.relay.features.partners.scout.InstagramProfileParser;
import com.expnai.relay.features.partners.scout.WebSearchHit;
import com.expnai.relay.features.partners.scout.YahooSearchParser;
import com.expnai.relay.features.product.ingest.SourceParserService.PassageChunk;

import lombok.RequiredArgsConstructor;

/**
 * Reads a public Instagram account for a product source. Instagram serves a login wall to browsers on
 * server IPs, so the profile bio, follower count and post captions are collected from the public
 * search-engine listing of the account (fetched through Playwright), the same text anyone sees
 * without logging in. Private accounts and posts without a caption yield nothing.
 */
@Service
@RequiredArgsConstructor
public class InstagramSourceReader {

	private static final Logger log = LoggerFactory.getLogger(InstagramSourceReader.class);

	private static final int WINDOW_TARGET_CHARS = 2000;
	private static final int MIN_CAPTION_CHARS = 15;
	private static final Set<String> POST_SEGMENTS = Set.of("p", "reel", "reels", "tv");
	private static final Pattern HANDLE = Pattern.compile("[A-Za-z0-9._]{1,30}");
	private static final Pattern POST_AUTHOR = Pattern.compile(
			"comments?\\s*-\\s*@?([A-Za-z0-9._]{1,30})\\s+\\S+\\s", Pattern.CASE_INSENSITIVE);
	private static final Pattern DATE_PREFIX = Pattern.compile("^[A-Z][a-z]{2,8} \\d{1,2}, \\d{4}\\s*[·\\-]\\s*");

	private final CreatorPageFetcher pageFetcher;
	private final YahooSearchParser yahooParser;
	private final InstagramProfileParser profileParser;
	private final InstagramProfilePageParser profilePageParser;

	@Value("${relay.instagram-source.time-budget-seconds:60}")
	private int timeBudgetSeconds = 60;

	@Value("${relay.instagram-source.max-posts:40}")
	private int maxPosts = 40;

	record Target(String handle, String postCode) {
	}

	record Post(String url, String date, String caption) {
	}

	public List<PassageChunk> read(String rawInput, Consumer<String> progress) {
		Instant deadline = Instant.now().plusSeconds(Math.max(10, timeBudgetSeconds));
		Target target = parseTarget(rawInput);
		if (target == null) {
			throw new IllegalArgumentException(
					"That does not look like an Instagram profile. Paste instagram.com/handle or @handle.");
		}
		String handle = target.handle();
		if (handle == null) {
			handle = resolveHandleFromPost(target.postCode(), deadline).orElseThrow(() -> new IllegalArgumentException(
					"Could not tell whose post that is. Paste the profile link instead (instagram.com/handle)."));
		}

		if (progress != null) {
			progress.accept("Instagram: reading @" + handle + " (public profile and recent posts)");
		}
		Map<String, Post> posts = new LinkedHashMap<>();
		InstagramProfile profile = collect(handle, posts, deadline);
		if (profile == null) {
			profile = readProfilePage(handle).orElse(null);
		}
		if (profile == null && posts.isEmpty()) {
			throw new IllegalArgumentException("Could not read any public Instagram content for @" + handle
					+ ". The account may be private or not indexed. Upload a transcript or export instead.");
		}

		List<PassageChunk> chunks = toChunks(handle, profile, new ArrayList<>(posts.values()));
		if (chunks.isEmpty()) {
			throw new IllegalArgumentException("No public captions or bio text found for @" + handle
					+ ". Upload a transcript or export instead.");
		}
		return chunks;
	}

	private InstagramProfile collect(String handle, Map<String, Post> posts, Instant deadline) {
		InstagramProfile profile = null;
		boolean anyResultsPage = false;
		for (String query : List.of("site:instagram.com \"" + handle + "\"", "site:instagram.com/" + handle)) {
			for (int page = 0; page < 3; page++) {
				if (!Instant.now().isBefore(deadline) || posts.size() >= maxPosts) {
					return profile;
				}
				List<WebSearchHit> hits;
				try {
					String html = pageFetcher.fetch("https://search.yahoo.com/search?ei=UTF-8&p="
							+ URLEncoder.encode(query, StandardCharsets.UTF_8)
							+ (page == 0 ? "" : "&b=" + (page * 10 + 1)));
					if (!yahooParser.looksLikeResultsPage(html)) {
						break;
					}
					anyResultsPage = true;
					hits = yahooParser.parse(html);
				}
				catch (RuntimeException ex) {
					log.warn("Instagram source search failed for @{}: {}", handle, ex.getMessage());
					break;
				}
				if (hits.isEmpty()) {
					break;
				}
				for (WebSearchHit hit : hits) {
					if (profile == null) {
						profile = profileOf(hit, handle);
					}
					parsePost(hit, handle).ifPresent(post -> posts.putIfAbsent(post.url(), post));
				}
			}
		}
		if (!anyResultsPage) {
			throw new IllegalArgumentException(
					"Instagram search is temporarily unavailable for @" + handle + ". Try again in a moment.");
		}
		return profile;
	}

	private InstagramProfile profileOf(WebSearchHit hit, String handle) {
		return profileParser.parse(hit)
				.filter(p -> p.handle().equalsIgnoreCase("@" + handle))
				.orElse(null);
	}

	private Optional<InstagramProfile> readProfilePage(String handle) {
		try {
			String html = pageFetcher.fetch("https://www.instagram.com/" + handle + "/");
			return profilePageParser.parse(html, handle)
					.filter(p -> !p.bio().isBlank() || !p.followers().isBlank());
		}
		catch (RuntimeException ex) {
			log.debug("Instagram profile page not readable for @{}: {}", handle, ex.getMessage());
			return Optional.empty();
		}
	}

	private Optional<String> resolveHandleFromPost(String postCode, Instant deadline) {
		if (!Instant.now().isBefore(deadline)) {
			return Optional.empty();
		}
		try {
			String html = pageFetcher.fetch("https://search.yahoo.com/search?ei=UTF-8&p="
					+ URLEncoder.encode("instagram.com/p/" + postCode, StandardCharsets.UTF_8));
			for (WebSearchHit hit : yahooParser.parse(html)) {
				if (hit.url() == null || !hit.url().contains(postCode)) {
					continue;
				}
				Matcher author = POST_AUTHOR.matcher(hit.snippet() == null ? "" : hit.snippet());
				if (author.find()) {
					return Optional.of(author.group(1));
				}
			}
		}
		catch (RuntimeException ex) {
			log.debug("Could not resolve Instagram post {}: {}", postCode, ex.getMessage());
		}
		return Optional.empty();
	}

	static Target parseTarget(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String trimmed = raw.trim();
		if (trimmed.startsWith("@")) {
			String handle = trimmed.substring(1).split("[/?\\s]")[0];
			return HANDLE.matcher(handle).matches() ? new Target(handle, null) : null;
		}
		try {
			URI uri = URI.create(trimmed.matches("(?i)^https?://.*") ? trimmed : "https://" + trimmed);
			String host = uri.getHost();
			if (host == null || !(host.equalsIgnoreCase("instagram.com") || host.toLowerCase(Locale.ROOT).endsWith(".instagram.com"))) {
				return null;
			}
			String[] segments = (uri.getPath() == null ? "" : uri.getPath()).replaceAll("^/+", "").split("/");
			if (segments.length == 0 || segments[0].isBlank()) {
				return null;
			}
			String first = segments[0];
			if (POST_SEGMENTS.contains(first.toLowerCase(Locale.ROOT))) {
				return segments.length > 1 && segments[1].matches("[A-Za-z0-9_-]{5,}")
						? new Target(null, segments[1]) : null;
			}
			return HANDLE.matcher(first).matches() ? new Target(first, null) : null;
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	static Optional<Post> parsePost(WebSearchHit hit, String handle) {
		String url = hit.url() == null ? "" : hit.url().trim();
		if (!url.contains("instagram.com/")) {
			return Optional.empty();
		}
		String path = url.replaceFirst("^https?://[^/]+", "").toLowerCase(Locale.ROOT);
		if (!(path.contains("/p/") || path.contains("/reel/") || path.contains("/tv/"))) {
			return Optional.empty();
		}
		String snippet = hit.snippet() == null ? "" : hit.snippet().trim();
		String quotedHandle = Pattern.quote(handle);
		boolean ownedByUrl = path.startsWith("/" + handle.toLowerCase(Locale.ROOT) + "/");
		Pattern captionPattern = Pattern.compile(
				"comments?\\s*-\\s*@?" + quotedHandle + "\\s+\\S+\\s+(.{3,40}?\\d{4}):\\s*[\"“](.*)$",
				Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
		Matcher matcher = captionPattern.matcher(snippet);
		String date = "";
		String caption;
		if (matcher.find()) {
			date = matcher.group(1).trim();
			caption = matcher.group(2);
		}
		else if (ownedByUrl) {
			caption = DATE_PREFIX.matcher(snippet).replaceFirst("");
		}
		else {
			return Optional.empty();
		}
		caption = caption.replaceAll("[\"”]\\s*\\.?\\s*$", "").replaceAll("\\s+", " ").trim();
		if (caption.length() < MIN_CAPTION_CHARS) {
			return Optional.empty();
		}
		return Optional.of(new Post(url, date, caption));
	}

	static List<PassageChunk> toChunks(String handle, InstagramProfile profile, List<Post> posts) {
		String locator = "@" + handle + " on Instagram";
		List<String> entries = new ArrayList<>();
		if (profile != null) {
			StringBuilder intro = new StringBuilder("Instagram profile @").append(handle);
			if (profile.name() != null && !profile.name().isBlank() && !profile.name().equalsIgnoreCase(handle)) {
				intro.append(" (").append(profile.name()).append(')');
			}
			if (!profile.followers().isBlank()) {
				intro.append(", ").append(profile.followers()).append(" followers");
			}
			intro.append('.');
			if (!profile.bio().isBlank()) {
				intro.append(" Bio: ").append(profile.bio());
			}
			entries.add(intro.toString());
		}
		for (Post post : posts) {
			entries.add((post.date().isBlank() ? "Post" : "Post from " + post.date()) + ": " + post.caption());
		}

		List<PassageChunk> chunks = new ArrayList<>();
		StringBuilder window = new StringBuilder();
		for (String entry : entries) {
			if (window.length() > 0 && window.length() + entry.length() > WINDOW_TARGET_CHARS) {
				chunks.add(new PassageChunk(window.toString().trim(), locator));
				window.setLength(0);
			}
			window.append(entry).append("\n\n");
		}
		if (window.length() > 0) {
			chunks.add(new PassageChunk(window.toString().trim(), locator));
		}
		return chunks;
	}
}
