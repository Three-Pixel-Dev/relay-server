package com.expnai.relay.features.market;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.expnai.relay.features.market.MarketResearchPack.GoogleSearchResult;
import com.expnai.relay.features.market.MarketResearchPack.GoogleTrendsResult;
import com.expnai.relay.features.product.ingest.PlaywrightWebFetchService;

import lombok.RequiredArgsConstructor;

/**
 * Playwright-backed Google Search and Google Trends client that reuses the shared
 * {@link PlaywrightWebFetchService} browser (Docker WS via {@code RELAY_WEB_PLAYWRIGHT_WS_ENDPOINT}
 * or local headless Chromium) when {@code RELAY_MARKET_GOOGLE_PROVIDER=playwright}.
 */
@Service
@RequiredArgsConstructor
public class PlaywrightGoogleClient {

	private final PlaywrightWebFetchService playwrightWebFetchService;

	@Value("${relay.market.google-provider:serpapi}")
	private String googleProvider;

	public boolean isConfigured() {
		return "playwright".equalsIgnoreCase(googleProvider == null ? "" : googleProvider.trim());
	}

	public GoogleSearchResult searchGoogle(String keyword) {
		if (!isConfigured()) {
			throw new IllegalStateException("RELAY_MARKET_GOOGLE_PROVIDER is not set to playwright");
		}
		return playwrightWebFetchService.scrapeGoogleSearch(keyword);
	}

	public GoogleTrendsResult fetchGoogleTrends(String keyword) {
		if (!isConfigured()) {
			throw new IllegalStateException("RELAY_MARKET_GOOGLE_PROVIDER is not set to playwright");
		}
		return playwrightWebFetchService.scrapeGoogleTrends(keyword);
	}
}
