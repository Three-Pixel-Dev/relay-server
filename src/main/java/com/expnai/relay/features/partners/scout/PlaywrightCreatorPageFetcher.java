package com.expnai.relay.features.partners.scout;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.expnai.relay.features.product.ingest.PlaywrightWebFetchService;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PlaywrightCreatorPageFetcher implements CreatorPageFetcher {

	/** Pre-accepts the consent wall so YouTube serves the normal page from any region. */
	private static final Map<String, String> YOUTUBE_COOKIES = Map.of("CONSENT", "YES+1", "SOCS", "CAI");

	private final PlaywrightWebFetchService playwrightWebFetchService;

	@Override
	public String fetch(String url) {
		Map<String, String> cookies = url.contains("youtube.com") ? YOUTUBE_COOKIES : Map.of();
		return playwrightWebFetchService.fetchLightHtml(url, cookies);
	}
}