package com.expnai.relay.features.partners.scout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class DuckDuckGoSearchParserTest {

	private final DuckDuckGoSearchParser parser = new DuckDuckGoSearchParser();

	static final String RESULTS_PAGE = """
			<html><body>
			<div class="result results_links results_links_deep web-result">
			  <a class="result__a" href="https://www.instagram.com/leesdogtraining/">Lee’s dog training</a>
			  <a class="result__snippet">56 Followers - Email:Leesdogtraining@aol.com</a>
			</div>
			<div class="result web-result">
			  <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.instagram.com%2Fotherdog%2F&rut=x">Other</a>
			  <a class="result__snippet">snippet</a>
			</div>
			</body></html>
			""";

	@Test
	void readsLinksAndUnwrapsDuckDuckGoRedirects() {
		List<WebSearchHit> hits = parser.parse(RESULTS_PAGE);

		assertEquals(2, hits.size());
		assertEquals("https://www.instagram.com/leesdogtraining/", hits.get(0).url());
		assertTrue(hits.get(0).snippet().contains("Leesdogtraining@aol.com"));
		assertEquals("https://www.instagram.com/otherdog/", hits.get(1).url());
	}

	@Test
	void recognisesAResultsPage() {
		assertTrue(parser.looksLikeResultsPage(RESULTS_PAGE));
	}
}
