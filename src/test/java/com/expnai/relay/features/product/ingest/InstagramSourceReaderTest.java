package com.expnai.relay.features.product.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.expnai.relay.features.partners.scout.CreatorPageFetcher;
import com.expnai.relay.features.partners.scout.InstagramProfileParser;
import com.expnai.relay.features.partners.scout.InstagramProfilePageParser;
import com.expnai.relay.features.partners.scout.WebSearchHit;
import com.expnai.relay.features.partners.scout.YahooSearchParser;
import com.expnai.relay.features.product.ingest.SourceParserService.PassageChunk;

class InstagramSourceReaderTest {

	private static final String PROFILE_SNIPPET = "1,815 Followers, 980 Following, 2,338 Posts - Collar Club (@winklercollarclub) "
			+ "on Instagram: \"Play Stay Belong Like Family. Doggie Daycare and Boarding\"";
	private static final String POST_SNIPPET = "192 likes, 12 comments - winklercollarclub el June 29, 2026: "
			+ "\"A New Chapter Begins at The Winkler Collar Club. The same commitment to loving your dog.\"";

	@Test
	void parsesHandlesFromProfileAndPostLinks() {
		assertEquals("someone", InstagramSourceReader.parseTarget("@someone").handle());
		assertEquals("someone", InstagramSourceReader.parseTarget("instagram.com/someone").handle());
		assertEquals("some.one_1", InstagramSourceReader.parseTarget("https://www.instagram.com/some.one_1/?hl=en").handle());
		assertEquals("someone", InstagramSourceReader.parseTarget("https://instagram.com/someone/reels/").handle());
		assertEquals("ABC123xyz", InstagramSourceReader.parseTarget("https://www.instagram.com/p/ABC123xyz/").postCode());
		assertNull(InstagramSourceReader.parseTarget("https://www.instagram.com/p/ABC123xyz/").handle());
		assertNull(InstagramSourceReader.parseTarget("https://example.com/someone"));
		assertNull(InstagramSourceReader.parseTarget("https://www.instagram.com/"));
		assertNull(InstagramSourceReader.parseTarget(""));
	}

	@Test
	void extractsCaptionAndDateFromAPostSnippet() {
		Optional<InstagramSourceReader.Post> post = InstagramSourceReader.parsePost(
				new WebSearchHit("https://www.instagram.com/p/DaLY2QSCQwK/", "Instagram", POST_SNIPPET),
				"winklercollarclub");

		assertTrue(post.isPresent());
		assertEquals("June 29, 2026", post.get().date());
		assertEquals("A New Chapter Begins at The Winkler Collar Club. The same commitment to loving your dog.",
				post.get().caption());
	}

	@Test
	void ignoresPostsFromOtherAccountsAndEmptyCaptions() {
		assertTrue(InstagramSourceReader.parsePost(
				new WebSearchHit("https://www.instagram.com/p/AAA111/", "Instagram", POST_SNIPPET), "someoneelse").isEmpty());
		assertTrue(InstagramSourceReader.parsePost(
				new WebSearchHit("https://www.instagram.com/p/AAA111/", "Instagram",
						"6 likes, 0 comments - winklercollarclub on July 24, 2026: \" \"."),
				"winklercollarclub").isEmpty());
		assertTrue(InstagramSourceReader.parsePost(
				new WebSearchHit("https://www.winklercollarclub.com/about", "About", POST_SNIPPET), "winklercollarclub").isEmpty());
	}

	@Test
	void readsProfileAndPostsFromSearchResults() {
		CreatorPageFetcher fetcher = mock(CreatorPageFetcher.class);
		when(fetcher.fetch(anyString())).thenReturn(resultsPage(
				result("https://www.instagram.com/winklercollarclub/", "Collar Club (@winklercollarclub) - Instagram", PROFILE_SNIPPET),
				result("https://www.instagram.com/p/DaLY2QSCQwK/", "Instagram", POST_SNIPPET)));

		List<PassageChunk> chunks = reader(fetcher).read("instagram.com/winklercollarclub", null);

		assertEquals(1, chunks.size());
		assertEquals("@winklercollarclub on Instagram", chunks.get(0).locator());
		assertTrue(chunks.get(0).text().contains("Instagram profile @winklercollarclub (Collar Club), 1,815 followers."));
		assertTrue(chunks.get(0).text().contains("Bio: Play Stay Belong"));
		assertTrue(chunks.get(0).text().contains("Post from June 29, 2026: A New Chapter Begins"));
		assertEquals(1, chunks.get(0).text().split("A New Chapter Begins", -1).length - 1);
	}

	@Test
	void explainsWhenNothingPublicIsFound() {
		CreatorPageFetcher fetcher = mock(CreatorPageFetcher.class);
		when(fetcher.fetch(anyString())).thenReturn(resultsPage());

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> reader(fetcher).read("@ghost_account", null));

		assertTrue(error.getMessage().contains("@ghost_account"));
	}

	@Test
	void reportsSearchOutageWhenNoResultsPageComesBack() {
		CreatorPageFetcher fetcher = mock(CreatorPageFetcher.class);
		when(fetcher.fetch(anyString())).thenThrow(new IllegalStateException("browser down"));

		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> reader(fetcher).read("@ghost_account", null));

		assertTrue(error.getMessage().contains("temporarily unavailable"));
	}

	@Test
	void rejectsLinksThatAreNotInstagramProfiles() {
		assertThrows(IllegalArgumentException.class,
				() -> reader(mock(CreatorPageFetcher.class)).read("https://example.com/x", null));
	}

	private static InstagramSourceReader reader(CreatorPageFetcher fetcher) {
		return new InstagramSourceReader(fetcher, new YahooSearchParser(), new InstagramProfileParser(),
				new InstagramProfilePageParser());
	}

	private static String result(String url, String title, String snippet) {
		return "<div class=\"algo\"><h3><a href=\"" + url + "\">" + title + "</a></h3>"
				+ "<div class=\"compText\"><p>" + snippet.replace("\"", "&quot;") + "</p></div></div>";
	}

	private static String resultsPage(String... results) {
		return "<html><body><div id=\"web\">" + String.join("", results) + "</div></body></html>";
	}
}
