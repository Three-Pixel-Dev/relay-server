package com.expnai.relay.features.partners.scout;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.expnai.relay.common.exception.BadGatewayException;
import com.expnai.relay.common.exception.BusinessRuleException;
import com.expnai.relay.features.partners.dto.response.RelayScoutResultResponse;

import lombok.RequiredArgsConstructor;

/**
 * Finds creators with a public business email. YouTube is read from the JSON embedded in its search and
 * channel About pages; Instagram is read from the profile description that Yahoo shows in its results
 * (Instagram itself shows nothing to visitors who are not logged in). All pages load through Playwright.
 */
@Service
@RequiredArgsConstructor
public class RelayCreatorScoutService {

	private static final Logger log = LoggerFactory.getLogger(RelayCreatorScoutService.class);

	private static final String YOUTUBE = "youtube";
	private static final String INSTAGRAM = "instagram";
	private static final int YOUTUBE_ABOUT_LOOKUPS = 10;
	private static final int INSTAGRAM_RESULT_PAGES = 2;
	private static final int SIGNAL_PREVIEW_CHARS = 120;
	private static final int CACHE_LIMIT = 200;

	private final CreatorPageFetcher pageFetcher;
	private final YoutubeCreatorParser youtubeParser;
	private final YahooSearchParser yahooParser;
	private final InstagramProfileParser instagramParser;
	private final CreatorSignalExtractor signals;

	@Value("${relay.scout.max-results:6}")
	private int maxResults = 6;

	@Value("${relay.scout.time-budget-seconds:50}")
	private int timeBudgetSeconds = 50;

	@Value("${relay.scout.cache-minutes:60}")
	private int cacheMinutes = 60;

	private final Map<String, CachedSearch> cache = new ConcurrentHashMap<>();

	public List<RelayScoutResultResponse> scout(String query, List<String> platforms, List<String> skipHandles) {
		String niche = query == null ? "" : query.trim();
		if (niche.isEmpty()) {
			throw new BusinessRuleException("Type a niche to search for, for example “reactive dogs”.");
		}
		List<String> selected = normalizePlatforms(platforms);
		Set<String> skip = (skipHandles == null ? List.<String>of() : skipHandles).stream()
				.map(handle -> handle.toLowerCase(Locale.ROOT))
				.collect(Collectors.toSet());

		String cacheKey = niche.toLowerCase(Locale.ROOT) + "|" + selected + "|" + skip.hashCode();
		CachedSearch cached = cache.get(cacheKey);
		if (cached != null && cached.storedAt().plus(Duration.ofMinutes(cacheMinutes)).isAfter(Instant.now())) {
			return cached.results();
		}

		int perPlatform = selected.size() == 1 ? maxResults : Math.max(2, (maxResults + 1) / 2 + 1);
		// Split the budget so YouTube About lookups cannot starve Instagram (or vice versa).
		long perPlatformBudgetSeconds = Math.max(15, timeBudgetSeconds / Math.max(1, selected.size()));
		String displayNiche = Character.toUpperCase(niche.charAt(0)) + niche.substring(1);

		List<List<Found>> perPlatformResults = new ArrayList<>();
		int failures = 0;
		for (String platform : selected) {
			Instant deadline = Instant.now().plusSeconds(perPlatformBudgetSeconds);
			try {
				List<Found> found = INSTAGRAM.equals(platform)
						? searchInstagram(niche, displayNiche, skip, perPlatform, deadline)
						: searchYoutube(niche, displayNiche, skip, perPlatform, deadline);
				perPlatformResults.add(found);
			}
			catch (RuntimeException ex) {
				failures++;
				log.warn("Creator search on {} failed: {}", platform, ex.getMessage());
			}
		}
		if (failures == selected.size()) {
			throw new BadGatewayException("Creator search is temporarily unavailable. Try again in a moment.");
		}

		List<RelayScoutResultResponse> results = interleave(perPlatformResults);
		if (!results.isEmpty()) {
			if (cache.size() >= CACHE_LIMIT) {
				cache.clear();
			}
			cache.put(cacheKey, new CachedSearch(Instant.now(), results));
		}
		return results;
	}

	private List<Found> searchInstagram(String niche, String displayNiche, Set<String> skip, int cap, Instant deadline) {
		String query = "site:instagram.com \"" + niche + "\" email";
		Map<String, Found> found = new LinkedHashMap<>();
		for (int page = 0; page < INSTAGRAM_RESULT_PAGES && found.size() < cap && Instant.now().isBefore(deadline); page++) {
			String url = "https://search.yahoo.com/search?ei=UTF-8&p=" + encode(query)
					+ (page == 0 ? "" : "&b=" + (page * 10 + 1));
			String html = pageFetcher.fetch(url);
			if (!yahooParser.looksLikeResultsPage(html)) {
				throw new IllegalStateException("The search engine did not return a results page");
			}
			for (WebSearchHit hit : yahooParser.parse(html)) {
				if (found.size() >= cap) {
					break;
				}
				Optional<InstagramProfile> profile = instagramParser.parse(hit);
				if (profile.isEmpty() || skip.contains(profile.get().handle().toLowerCase(Locale.ROOT))) {
					continue;
				}
				toInstagramResult(profile.get(), displayNiche)
						.ifPresent(result -> found.putIfAbsent(result.response().handle(), result));
			}
		}
		return List.copyOf(found.values());
	}

	private Optional<Found> toInstagramResult(InstagramProfile profile, String niche) {
		Optional<String> email = signals.findEmail(profile.bio());
		if (email.isEmpty()) {
			return Optional.empty();
		}
		Optional<String> listSignal = signals.listSignal("Bio", profile.bio());
		String signal = listSignal.orElseGet(() -> preview("Bio", profile.bio()));
		RelayScoutResultResponse response = new RelayScoutResultResponse(
				profile.name(),
				profile.handle(),
				INSTAGRAM,
				"instagram.com/" + profile.handle().substring(1),
				profile.followers().isEmpty() ? "n/a" : profile.followers(),
				niche,
				signal,
				signals.listSize(profile.bio()),
				signals.productNote(profile.bio()),
				email.get());
		return Optional.of(new Found(response, listSignal.isPresent()));
	}

	private List<Found> searchYoutube(String niche, String displayNiche, Set<String> skip, int cap, Instant deadline) {
		String searchUrl = "https://www.youtube.com/results?search_query=" + encode(niche) + "&sp=EgIQAg%253D%253D";
		List<YoutubeChannelHit> channels = youtubeParser.parseSearch(pageFetcher.fetch(searchUrl));
		if (channels.isEmpty()) {
			throw new IllegalStateException("YouTube returned no channels to read");
		}

		List<Found> found = new ArrayList<>();
		int lookups = 0;
		for (YoutubeChannelHit channel : channels) {
			if (found.size() >= cap || lookups >= YOUTUBE_ABOUT_LOOKUPS || !Instant.now().isBefore(deadline)) {
				break;
			}
			String handle = channel.path().replaceAll("^/+", "");
			if (skip.contains(handle.toLowerCase(Locale.ROOT))) {
				continue;
			}
			lookups++;
			try {
				toYoutubeResult(channel, handle, displayNiche).ifPresent(found::add);
			}
			catch (RuntimeException ex) {
				log.debug("Skipping YouTube channel {}: {}", handle, ex.getMessage());
			}
		}
		return found;
	}

	private Optional<Found> toYoutubeResult(YoutubeChannelHit channel, String handle, String niche) {
		Optional<YoutubeChannelAbout> about = youtubeParser
				.parseAbout(pageFetcher.fetch("https://www.youtube.com/" + handle + "/about"));
		String aboutText = about.map(a -> a.description() + "\n" + a.links()).orElse("");
		Optional<String> email = signals.findEmail(aboutText, channel.snippet());
		if (email.isEmpty()) {
			return Optional.empty();
		}
		String subscribers = !channel.subscribers().isBlank()
				? channel.subscribers()
				: about.map(YoutubeChannelAbout::subscribers).orElse("");
		Optional<String> listSignal = signals.listSignal("About", aboutText);
		RelayScoutResultResponse response = new RelayScoutResultResponse(
				channel.name(),
				handle.startsWith("@") ? handle : "@" + handle,
				YOUTUBE,
				"youtube.com/" + handle,
				compactSubscribers(subscribers),
				niche,
				listSignal.orElseGet(() -> preview("About", aboutText)),
				signals.listSize(aboutText),
				signals.productNote(aboutText),
				email.get());
		return Optional.of(new Found(response, listSignal.isPresent()));
	}

	private static List<RelayScoutResultResponse> interleave(List<List<Found>> perPlatformResults) {
		List<List<Found>> ranked = perPlatformResults.stream()
				.map(list -> list.stream().sorted(Comparator.comparing(Found::listed).reversed()).toList())
				.toList();
		List<RelayScoutResultResponse> out = new ArrayList<>();
		int round = 0;
		boolean added = true;
		while (added) {
			added = false;
			for (List<Found> list : ranked) {
				if (round < list.size()) {
					out.add(list.get(round).response());
					added = true;
				}
			}
			round++;
		}
		return List.copyOf(out);
	}

	private List<String> normalizePlatforms(List<String> platforms) {
		List<String> selected = (platforms == null ? List.<String>of() : platforms).stream()
				.map(platform -> platform.toLowerCase(Locale.ROOT))
				.filter(platform -> YOUTUBE.equals(platform) || INSTAGRAM.equals(platform))
				.distinct()
				.toList();
		return selected.isEmpty() ? List.of(YOUTUBE, INSTAGRAM) : selected;
	}

	private static String compactSubscribers(String raw) {
		String value = raw == null ? "" : raw.replaceAll("(?i)\\s*subscribers?", "").trim();
		return value.isEmpty() ? "n/a" : value.replace(" ", "").toLowerCase(Locale.ROOT);
	}

	private static String preview(String label, String text) {
		String clean = text == null ? "" : text.replaceAll("\\s+", " ").trim();
		if (clean.length() > SIGNAL_PREVIEW_CHARS) {
			clean = clean.substring(0, SIGNAL_PREVIEW_CHARS) + "…";
		}
		return clean.isEmpty() ? "No email list mentioned" : label + ": “" + clean + "”";
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private record Found(RelayScoutResultResponse response, boolean listed) {
	}

	private record CachedSearch(Instant storedAt, List<RelayScoutResultResponse> results) {
	}
}
