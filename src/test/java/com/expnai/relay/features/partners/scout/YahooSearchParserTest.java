package com.expnai.relay.features.partners.scout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class YahooSearchParserTest {

	private final YahooSearchParser parser = new YahooSearchParser();

	static final String RESULTS_PAGE = """
			<html><body><div id="web"><ol>
			<li><div class="dd algo algo-sr"><div class="compTitle"><a href="https://www.instagram.com/leesdogtraining/">
			<h3 class="title"><span>Lee’s dog training (@leesdogtraining) • Instagram photos and ...</span></h3></a></div>
			<div class="compText"><p> 56 Followers, 30 Following, 7 Posts - Lee’s dog training (@leesdogtraining) on Instagram: "Mobile dog training.<b> Email</b>:Leesdogtraining@aol.com" </p></div></div></li>
			<li><div class="dd algo algo-sr"><div class="compTitle"><a href="https://www.instagram.com/p/XYZ/">
			<h3 class="title"><span>A post</span></h3></a></div>
			<div class="compText"><p>post text</p></div></div></li>
			</ol></div></body></html>
			""";

	@Test
	void readsLinkTitleAndSnippetOfEachOrganicResult() {
		List<WebSearchHit> hits = parser.parse(RESULTS_PAGE);

		assertEquals(2, hits.size());
		assertEquals("https://www.instagram.com/leesdogtraining/", hits.get(0).url());
		assertTrue(hits.get(0).title().startsWith("Lee’s dog training"));
		assertTrue(hits.get(0).snippet().contains("Email:Leesdogtraining@aol.com"));
	}

	@Test
	void recognisesAResultsPageAndRejectsABlockPage() {
		assertTrue(parser.looksLikeResultsPage(RESULTS_PAGE));
		assertFalse(parser.looksLikeResultsPage("<html><body>Unusual traffic</body></html>"));
		assertTrue(parser.parse("").isEmpty());
	}
}