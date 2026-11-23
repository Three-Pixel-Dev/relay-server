package com.expnai.relay.features.partners.scout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class YoutubeCreatorParserTest {

	private final YoutubeCreatorParser parser = new YoutubeCreatorParser(new ObjectMapper());

	static final String SEARCH_PAGE = """
			<html><script>var ytInitialData = {"contents":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[
			{"channelRenderer":{"channelId":"UC1","title":{"simpleText":"Hamilton Dog Training"},
			"navigationEndpoint":{"browseEndpoint":{"canonicalBaseUrl":"/@hamiltondogtraining"}},
			"descriptionSnippet":{"runs":[{"text":"The "},{"text":"Dog Training","bold":true},{"text":" School"}]},
			"videoCountText":{"simpleText":"125K subscribers"},"subscriberCountText":{"simpleText":"@hamiltondogtraining"}}},
			{"videoRenderer":{"videoId":"v1"}},
			{"channelRenderer":{"channelId":"UC2","title":{"simpleText":"No Handle"},
			"navigationEndpoint":{"browseEndpoint":{}},"videoCountText":{"simpleText":"3K subscribers"}}}
			]}}]}}};</script><script>other()</script></html>
			""";

	static final String ABOUT_PAGE = """
			<html><script>var ytInitialData = {"metadata":{"channelMetadataRenderer":{"description":"Training tips. Business: hi@hamilton.co"}},
			"contents":{"x":{"aboutChannelViewModel":{"description":"ignored when metadata has one","subscriberCountText":"125K subscribers",
			"links":[{"channelExternalLinkViewModel":{"title":{"content":"Free newsletter"},"link":{"content":"hamilton.beehiiv.com"}}}]}}}};</script></html>
			""";

	@Test
	void readsChannelsFromSearchResultsAndSkipsVideos() {
		List<YoutubeChannelHit> hits = parser.parseSearch(SEARCH_PAGE);

		assertEquals(2, hits.size());
		assertEquals("/@hamiltondogtraining", hits.get(0).path());
		assertEquals("Hamilton Dog Training", hits.get(0).name());
		assertEquals("125K subscribers", hits.get(0).subscribers());
		assertEquals("The Dog Training School", hits.get(0).snippet());
		assertEquals("/channel/UC2", hits.get(1).path());
	}

	static final String LOCKUP_SEARCH_PAGE = """
			<html><script>var ytInitialData = {"contents":[{"lockupViewModel":{"contentType":"LOCKUP_CONTENT_TYPE_CHANNEL","contentId":"UC9",
			"contentImage":{"x":{"browseEndpoint":{"canonicalBaseUrl":"/@newlayout"}}},
			"metadata":{"lockupMetadataViewModel":{"title":{"content":"New Layout Dogs"},"metadata":{"contentMetadataViewModel":{"metadataRows":[
			{"metadataParts":[{"text":{"content":"@newlayout"}},{"text":{"content":"88K subscribers"}}]},
			{"metadataParts":[{"text":{"content":"Calm dog tips every week"}}]}]}}}}}},
			{"lockupViewModel":{"contentType":"LOCKUP_CONTENT_TYPE_VIDEO","contentId":"vid"}}]};</script></html>
			""";

	@Test
	void readsChannelsFromTheNewerSearchLayout() {
		List<YoutubeChannelHit> hits = parser.parseSearch(LOCKUP_SEARCH_PAGE);

		assertEquals(1, hits.size());
		assertEquals("/@newlayout", hits.get(0).path());
		assertEquals("New Layout Dogs", hits.get(0).name());
		assertEquals("88K subscribers", hits.get(0).subscribers());
		assertEquals("Calm dog tips every week", hits.get(0).snippet());
	}

	@Test
	void readsDescriptionAndLinksFromTheAboutPage() {
		YoutubeChannelAbout about = parser.parseAbout(ABOUT_PAGE).orElseThrow();

		assertTrue(about.description().contains("hi@hamilton.co"));
		assertTrue(about.links().contains("hamilton.beehiiv.com"));
		assertEquals("125K subscribers", about.subscribers());
	}

	@Test
	void returnsNothingWhenThePageHasNoEmbeddedData() {
		assertTrue(parser.parseSearch("<html>consent wall</html>").isEmpty());
		assertTrue(parser.parseAbout("<html>consent wall</html>").isEmpty());
		assertTrue(parser.parseSearch(null).isEmpty());
	}
}