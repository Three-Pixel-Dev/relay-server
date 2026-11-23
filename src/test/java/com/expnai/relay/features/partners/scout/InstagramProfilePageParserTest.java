package com.expnai.relay.features.partners.scout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InstagramProfilePageParserTest {

	private final InstagramProfilePageParser parser = new InstagramProfilePageParser();

	static final String PROFILE_PAGE = """
			<html><head>
			<meta property="og:title" content="Lee’s dog training (@leesdogtraining) • Instagram photos and videos" />
			<meta property="og:description" content="56 Followers, 30 Following, 7 Posts - See Instagram photos and videos from Lee’s dog training (@leesdogtraining)" />
			</head><body>
			<script>window._sharedData = {"entry_data":{"ProfilePage":[{"graphql":{"user":{"biography":"Mobile dog training. Email: Leesdogtraining@aol.com"}}}]}};</script>
			</body></html>
			""";

	@Test
	void readsNameFollowersAndBiographyFromThePublicProfilePage() {
		InstagramProfile profile = parser.parse(PROFILE_PAGE, "leesdogtraining").orElseThrow();

		assertEquals("@leesdogtraining", profile.handle());
		assertEquals("Lee’s dog training", profile.name());
		assertEquals("56", profile.followers());
		assertTrue(profile.bio().contains("Leesdogtraining@aol.com"));
	}

	@Test
	void recognisesAProfilePage() {
		assertTrue(InstagramProfilePageParser.looksLikeProfilePage(PROFILE_PAGE));
	}
}
