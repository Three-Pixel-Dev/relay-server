package com.expnai.relay.features.market;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

	@Value("${relay.market.marketplace-provider:auto}")
	private String marketplaceProvider;

	public MarketResearchPack research(List<String> keywords, Consumer<String> progressCallback) {
		List<String> cleanKeywords = keywords == null
				? List.of()
				: keywords.stream()
						.filter(k -> k != null && !k.isBlank())
						.map(String::trim)
						.distinct()
						.limit(6)
						.toList();

		if (cleanKeywords.isEmpty()) {
			throw new BusinessRuleException("No keywords available for market research.");
		}

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

			// 2. SerpAPI Google Search
			if (progressCallback != null) {
				progressCallback.accept("Checking Google demand");
			}
			if (!serpApiClient.isConfigured()) {
				missingProviders.add("SerpAPI Google Search (SERPAPI_API_KEY not configured)");
				missingProviders.add("Google Trends via SerpAPI (SERPAPI_API_KEY not configured)");
			}
			else {
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

				// 3. SerpAPI Google Trends
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

		if (pack.isEmpty()) {
			throw new BusinessRuleException(
					"Market research could not reach any live data provider ("
							+ String.join("; ", missingProviders)
							+ "). Set ETSY_API_KEY, APIFY_TOKEN, and/or SERPAPI_API_KEY on relay-server and press Proceed again.");
		}
		return pack;
	}

	private void runMarketplacePhase(
			ExecutorService pool,
			List<String> keywords,
			List<EtsyKeywordResult> etsyResults,
			List<String> missingProviders) {
		String mode = normalizeMarketplaceMode(marketplaceProvider);

		if ("etsy".equals(mode)) {
			if (!etsyClient.isConfigured()) {
				missingProviders.add("Etsy Open API v3 (ETSY_API_KEY not configured)");
				return;
			}
			List<String> failed = runEtsyQueries(pool, keywords, etsyResults);
			if (etsyResults.isEmpty() && !failed.isEmpty()) {
				missingProviders.add("Etsy Open API v3 (request failed for all keywords)");
			}
			return;
		}

		if ("apify".equals(mode)) {
			if (!apifyClient.isConfigured()) {
				missingProviders.add("Etsy via Apify (APIFY_TOKEN not configured)");
				return;
			}
			runApifyQueries(pool, keywords, etsyResults);
			if (etsyResults.isEmpty()) {
				missingProviders.add("Etsy via Apify (request failed for all keywords)");
			}
			return;
		}

		// mode == "auto": try Etsy Open API first, fall back to Apify when unconfigured or per failed keyword
		boolean etsyReady = etsyClient.isConfigured();
		boolean apifyReady = apifyClient.isConfigured();
		if (!etsyReady && !apifyReady) {
			missingProviders.add("Etsy Marketplace (neither ETSY_API_KEY nor APIFY_TOKEN configured)");
			return;
		}

		List<String> fallbackKeywords;
		if (etsyReady) {
			fallbackKeywords = runEtsyQueries(pool, keywords, etsyResults);
		}
		else {
			fallbackKeywords = keywords;
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
					etsyResults.add(etsyClient.searchActiveListings(kw));
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
					etsyResults.add(apifyClient.searchActiveListings(kw));
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

