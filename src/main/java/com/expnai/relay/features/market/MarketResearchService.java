package com.expnai.relay.features.market;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.expnai.relay.common.exception.BusinessRuleException;
import com.expnai.relay.common.logging.RelayTrace;
import com.expnai.relay.features.market.MarketResearchPack.EtsyKeywordResult;
import com.expnai.relay.features.market.MarketResearchPack.GoogleSearchResult;
import com.expnai.relay.features.market.MarketResearchPack.GoogleTrendsResult;

import lombok.RequiredArgsConstructor;

/**
 * Orchestrates bounded-concurrency market research across Etsy Open API v3,
 * SerpAPI Google Search, and SerpAPI Google Trends.
 * <p>
 * On single-provider failure, records the missing provider and continues with remaining providers.
 * If all providers fail or are unconfigured, throws {@link BusinessRuleException}.
 */
@Service
@RequiredArgsConstructor
public class MarketResearchService {

	private static final Logger log = LoggerFactory.getLogger(MarketResearchService.class);
	private static final int MAX_CONCURRENCY = 3;

	private final EtsyListingSearchClient etsyClient;
	private final ApifyEtsySearchClient apifyClient;
	private final SerpApiClient serpApiClient;
	private final PlaywrightGoogleClient playwrightGoogleClient;
	private final MarketplaceKeywordCache keywordCache;

	@Value("${relay.market.marketplace-provider:auto}")
	private String marketplaceProvider;

	@Value("${relay.market.google-provider:serpapi}")
	private String googleProvider;

	public MarketResearchPack research(List<String> keywords, Consumer<String> progressCallback) {
		List<String> cleanKeywords = keywords == null
				? List.of()
				: keywords.stream()
						.filter(k -> k != null && !k.isBlank())
						.map(String::trim)
						.distinct()
						.limit(3)
						.toList();

		if (cleanKeywords.isEmpty()) {
			throw new BusinessRuleException("No keywords available for market research.");
		}

		RelayTrace.scrape("market research start keywords={} marketplaceProvider={} googleProvider={}",
				cleanKeywords, normalizeMarketplaceMode(marketplaceProvider), normalizeGoogleMode(googleProvider));

		String queriedAt = LocalDate.now(ZoneOffset.UTC).toString();
		List<EtsyKeywordResult> etsyResults = new CopyOnWriteArrayList<>();
		List<GoogleSearchResult> googleResults = new CopyOnWriteArrayList<>();
		List<GoogleTrendsResult> trendsResults = new CopyOnWriteArrayList<>();
		List<String> missingProviders = new CopyOnWriteArrayList<>();

		ExecutorService pool = Executors.newFixedThreadPool(MAX_CONCURRENCY);
		try {
			// 1. Etsy Marketplace (env-switched: auto | etsy | apify)
			if (progressCallback != null) {
				progressCallback.accept("Reading marketplace listings");
			}
			runMarketplacePhase(pool, cleanKeywords, etsyResults, missingProviders);

			// 2 & 3. Google Search + Google Trends (env-switched: serpapi | playwright)
			runGoogleAndTrendsPhase(pool, cleanKeywords, googleResults, trendsResults, missingProviders, progressCallback);
		}
		finally {
			pool.shutdownNow();
		}

		MarketResearchPack pack = new MarketResearchPack(
				queriedAt,
				cleanKeywords,
				List.copyOf(etsyResults),
				List.copyOf(googleResults),
				List.copyOf(trendsResults),
				List.copyOf(missingProviders));

		RelayTrace.scrape("market research done etsy={} google={} trends={} missing={}",
				etsyResults.size(), googleResults.size(), trendsResults.size(), missingProviders.size());

		if (pack.isEmpty()) {
			throw new BusinessRuleException(
					"Market research could not reach any live data provider ("
							+ String.join("; ", missingProviders)
							+ "). Check ETSY_API_KEY, APIFY_TOKEN, SERPAPI_API_KEY, or RELAY_WEB_PLAYWRIGHT_WS_ENDPOINT on relay-server and press Proceed again.");
		}
		return pack;
	}

	private void runGoogleAndTrendsPhase(
			ExecutorService pool,
			List<String> cleanKeywords,
			List<GoogleSearchResult> googleResults,
			List<GoogleTrendsResult> trendsResults,
			List<String> missingProviders,
			Consumer<String> progressCallback) {
		String mode = normalizeGoogleMode(googleProvider);

		if (progressCallback != null) {
			progressCallback.accept("Checking Google demand");
		}

		if ("playwright".equals(mode)) {
			if (!playwrightGoogleClient.isConfigured()) {
				missingProviders.add("Playwright Google Search (RELAY_MARKET_GOOGLE_PROVIDER not enabled)");
				missingProviders.add("Playwright Google Trends (RELAY_MARKET_GOOGLE_PROVIDER not enabled)");
				return;
			}

			List<Future<?>> googleFutures = new ArrayList<>();
			for (String kw : cleanKeywords) {
				googleFutures.add(pool.submit(() -> {
					try {
						googleResults.add(playwrightGoogleClient.searchGoogle(kw));
					}
					catch (Exception ex) {
						log.warn("Playwright Google lookup failed for '{}': {}", kw, ex.getMessage());
					}
				}));
			}
			awaitAll(googleFutures);
			if (googleResults.isEmpty()) {
				missingProviders.add("Playwright Google Search (Playwright WS/browser unreachable or failed for all keywords; check RELAY_WEB_PLAYWRIGHT_WS_ENDPOINT)");
			}

			if (progressCallback != null) {
				progressCallback.accept("Reading Trends");
			}
			List<Future<?>> trendsFutures = new ArrayList<>();
			for (String kw : cleanKeywords.stream().limit(4).toList()) {
				trendsFutures.add(pool.submit(() -> {
					try {
						trendsResults.add(playwrightGoogleClient.fetchGoogleTrends(kw));
					}
					catch (Exception ex) {
						log.warn("Playwright Google Trends lookup failed for '{}': {}", kw, ex.getMessage());
					}
				}));
			}
			awaitAll(trendsFutures);
			if (trendsResults.isEmpty()) {
				missingProviders.add("Playwright Google Trends (Playwright WS/browser unreachable or no timeline data returned; check RELAY_WEB_PLAYWRIGHT_WS_ENDPOINT)");
			}
			return;
		}

		// Default: serpapi
		if (!serpApiClient.isConfigured()) {
			missingProviders.add("SerpAPI Google Search (SERPAPI_API_KEY not configured)");
			missingProviders.add("Google Trends via SerpAPI (SERPAPI_API_KEY not configured)");
			return;
		}

		List<Future<?>> googleFutures = new ArrayList<>();
		for (String kw : cleanKeywords) {
			googleFutures.add(pool.submit(() -> {
				try {
					googleResults.add(serpApiClient.searchGoogle(kw));
				}
				catch (Exception ex) {
					log.warn("SerpAPI Google lookup failed for '{}': {}", kw, ex.getMessage());
				}
			}));
		}
		awaitAll(googleFutures);
		if (googleResults.isEmpty()) {
			missingProviders.add("SerpAPI Google Search (request failed for all keywords)");
		}

		if (progressCallback != null) {
			progressCallback.accept("Reading Trends");
		}
		List<Future<?>> trendsFutures = new ArrayList<>();
		// Limit Trends queries to top 4 keywords to stay fast & within quota
		for (String kw : cleanKeywords.stream().limit(4).toList()) {
			trendsFutures.add(pool.submit(() -> {
				try {
					trendsResults.add(serpApiClient.fetchGoogleTrends(kw));
				}
				catch (Exception ex) {
					log.warn("SerpAPI Google Trends lookup failed for '{}': {}", kw, ex.getMessage());
				}
			}));
		}
		awaitAll(trendsFutures);
		if (trendsResults.isEmpty()) {
			missingProviders.add("Google Trends via SerpAPI (no timeline data returned)");
		}
	}

	private String normalizeGoogleMode(String raw) {
		if (raw == null || raw.isBlank()) {
			return "serpapi";
		}
		String lower = raw.trim().toLowerCase(Locale.ROOT);
		if ("playwright".equals(lower)) {
			return "playwright";
		}
		return "serpapi";
	}

	/**
	 * Lightweight marketplace-only search (uses the same auto|etsy|apify provider switch)
	 * for per-format price research. Never throws if providers fail or are unconfigured.
	 */
	public Map<String, EtsyKeywordResult> searchMarketplaceKeywords(List<String> keywords) {
		List<String> cleanKeywords = keywords == null
				? List.of()
				: keywords.stream()
						.filter(k -> k != null && !k.isBlank())
						.map(String::trim)
						.distinct()
						.limit(3)
						.toList();
		if (cleanKeywords.isEmpty()) {
			return Map.of();
		}
		List<EtsyKeywordResult> results = new CopyOnWriteArrayList<>();
		List<String> missing = new CopyOnWriteArrayList<>();
		ExecutorService pool = Executors.newFixedThreadPool(MAX_CONCURRENCY);
		try {
			runMarketplacePhase(pool, cleanKeywords, results, missing);
		}
		finally {
			pool.shutdownNow();
		}
		Map<String, EtsyKeywordResult> byKeyword = new LinkedHashMap<>();
		for (EtsyKeywordResult r : results) {
			if (r != null && r.keyword() != null) {
				byKeyword.put(r.keyword(), r);
			}
		}
		return byKeyword;
	}

	private void runMarketplacePhase(
			ExecutorService pool,
			List<String> keywords,
			List<EtsyKeywordResult> etsyResults,
			List<String> missingProviders) {
		List<String> uncachedKeywords = new ArrayList<>();
		for (String kw : keywords) {
			var cached = keywordCache.get(kw);
			if (cached.isPresent()) {
				etsyResults.add(cached.get());
			}
			else {
				uncachedKeywords.add(kw);
			}
		}
		if (uncachedKeywords.isEmpty()) {
			return;
		}

		String mode = normalizeMarketplaceMode(marketplaceProvider);

		if ("etsy".equals(mode)) {
			if (!etsyClient.isConfigured()) {
				if (etsyResults.isEmpty()) {
					missingProviders.add("Etsy Open API v3 (ETSY_API_KEY not configured)");
				}
				return;
			}
			List<String> failed = runEtsyQueries(pool, uncachedKeywords, etsyResults);
			if (etsyResults.isEmpty() && !failed.isEmpty()) {
				missingProviders.add("Etsy Open API v3 (request failed for all keywords)");
			}
			return;
		}

		if ("apify".equals(mode)) {
			if (!apifyClient.isConfigured()) {
				if (etsyResults.isEmpty()) {
					missingProviders.add("Etsy via Apify (APIFY_TOKEN not configured)");
				}
				return;
			}
			runApifyQueries(pool, uncachedKeywords, etsyResults);
			if (etsyResults.isEmpty()) {
				missingProviders.add("Etsy via Apify (request failed for all keywords)");
			}
			return;
		}

		// mode == "auto": try Etsy Open API first, fall back to Apify when unconfigured or per failed keyword
		boolean etsyReady = etsyClient.isConfigured();
		boolean apifyReady = apifyClient.isConfigured();
		if (!etsyReady && !apifyReady) {
			if (etsyResults.isEmpty()) {
				missingProviders.add("Etsy Marketplace (neither ETSY_API_KEY nor APIFY_TOKEN configured)");
			}
			return;
		}

		List<String> fallbackKeywords;
		if (etsyReady) {
			fallbackKeywords = runEtsyQueries(pool, uncachedKeywords, etsyResults);
		}
		else {
			fallbackKeywords = uncachedKeywords;
		}

		if (!fallbackKeywords.isEmpty() && apifyReady) {
			runApifyQueries(pool, fallbackKeywords, etsyResults);
		}

		if (etsyResults.isEmpty()) {
			if (etsyReady && apifyReady) {
				missingProviders.add("Etsy Open API v3 and Etsy via Apify (requests failed for all keywords)");
			}
			else if (etsyReady) {
				missingProviders.add("Etsy Open API v3 (failed) and Apify (APIFY_TOKEN not configured)");
			}
			else {
				missingProviders.add("Etsy Open API v3 (ETSY_API_KEY not configured) and Etsy via Apify (request failed)");
			}
		}
	}

	private List<String> runEtsyQueries(
			ExecutorService pool,
			List<String> keywords,
			List<EtsyKeywordResult> etsyResults) {
		List<String> failed = new CopyOnWriteArrayList<>();
		List<Future<?>> futures = new ArrayList<>();
		for (String kw : keywords) {
			futures.add(pool.submit(() -> {
				try {
					EtsyKeywordResult res = etsyClient.searchActiveListings(kw);
					keywordCache.put(kw, res);
					etsyResults.add(res);
				}
				catch (Exception ex) {
					log.warn("Etsy Open API lookup failed for '{}': {}", kw, ex.getMessage());
					failed.add(kw);
				}
			}));
		}
		awaitAll(futures);
		return List.copyOf(failed);
	}

	private void runApifyQueries(
			ExecutorService pool,
			List<String> keywords,
			List<EtsyKeywordResult> etsyResults) {
		List<Future<?>> futures = new ArrayList<>();
		for (String kw : keywords) {
			futures.add(pool.submit(() -> {
				try {
					EtsyKeywordResult res = apifyClient.searchActiveListings(kw);
					keywordCache.put(kw, res);
					etsyResults.add(res);
				}
				catch (Exception ex) {
					log.warn("Apify Etsy lookup failed for '{}': {}", kw, ex.getMessage());
				}
			}));
		}
		awaitAll(futures);
	}

	private String normalizeMarketplaceMode(String raw) {
		if (raw == null || raw.isBlank()) {
			return "auto";
		}
		String lower = raw.trim().toLowerCase(Locale.ROOT);
		if ("etsy".equals(lower) || "apify".equals(lower) || "auto".equals(lower)) {
			return lower;
		}
		return "auto";
	}

	private void awaitAll(List<Future<?>> futures) {
		for (Future<?> f : futures) {
			try {
				f.get();
			}
			catch (Exception ignored) {
			}
		}
	}
}

