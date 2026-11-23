package com.expnai.relay.features.partners.scout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InstagramProfileParserTest {

	private final InstagramProfileParser parser = new InstagramProfileParser();

	@Test
	void readsNameFollowersAndBioFromTheSearchSnippet() {
		WebSearchHit hit = new WebSearchHit(
				"https://www.instagram.com/libertydogtraining/",
				"Jennifer-Liberty Dog Training (@libertydogtraining) - Instagram",
				"2,019 Followers, 1,050 Following, 779 Posts - Jennifer-Liberty Dog Training (@libertydogtraining) "
						+ "on Instagram: \"Helping dogs live better Email: libertydogtrainingaz@gmail.com\"");

		InstagramProfile profile = parser.parse(hit).orElseThrow();

		assertEquals("@libertydogtraining", profile.handle());
		assertEquals("Jennifer-Liberty Dog Training", profile.name());
		assertEquals("2,019", profile.followers());
		assertEquals("Helping dogs live better Email: libertydogtrainingaz@gmail.com", profile.bio());
	}

	@Test
	void keepsLargeFollowerCountsCompact() {
		WebSearchHit hit = new WebSearchHit(
				"https://www.instagram.com/dog_training_site/",
				"dog training (@dog_training_site) • Instagram photos and videos",
				"276K Followers, 91 Following, 338 Posts - dog training (@dog_training_site) on Instagram: \"hi\"");

		assertEquals("276k", parser.parse(hit).orElseThrow().followers());
	}

	@Test
	void dropsInstagramsBoilerplateFromTheDisplayName() {
		WebSearchHit hit = new WebSearchHit(
				"https://www.instagram.com/k9_pro/",
				"See Instagram photos and videos from K9 Pro (@k9_pro)",
				"");

		assertEquals("K9 Pro", parser.parse(hit).orElseThrow().name());
	}

	@Test
	void ignoresPostsReelsAndNonInstagramLinks() {
		assertTrue(parser.parse(new WebSearchHit("https://www.instagram.com/p/ABC123/", "t", "s")).isEmpty());
		assertTrue(parser.parse(new WebSearchHit("https://www.instagram.com/reel/ABC123/", "t", "s")).isEmpty());
		assertTrue(parser.parse(new WebSearchHit("https://help.instagram.com/", "t", "s")).isEmpty());
		assertTrue(parser.parse(new WebSearchHit("https://example.com/someone", "t", "s")).isEmpty());
		assertTrue(parser.parse(new WebSearchHit("https://www.instagram.com/someone/reels/", "t", "s")).isEmpty());
	}
}