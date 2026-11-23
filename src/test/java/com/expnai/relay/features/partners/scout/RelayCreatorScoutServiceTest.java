package com.expnai.relay.features.partners.scout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.expnai.relay.common.exception.BadGatewayException;
import com.expnai.relay.common.exception.BusinessRuleException;
import com.expnai.relay.features.partners.dto.response.RelayScoutResultResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

class RelayCreatorScoutServiceTest {

	private final List<String> fetched = new ArrayList<>();

	private RelayCreatorScoutService service(CreatorPageFetcher fetcher) {
		return new RelayCreatorScoutService(
				fetcher,
				new YoutubeCreatorParser(new ObjectMapper()),
				new YahooSearchParser(),
				new InstagramProfileParser(),
				new CreatorSignalExtractor());
	}

	private CreatorPageFetcher working() {
		return url -> {
			fetched.add(url);
			if (url.contains("search.yahoo.com")) {
				return YahooSearchParserTest.RESULTS_PAGE;
			}
			if (url.contains("youtube.com/results")) {
				return YoutubeCreatorParserTest.SEARCH_PAGE;
			}
			if (url.contains("/@hamiltondogtraining/about")) {
				return YoutubeCreatorParserTest.ABOUT_PAGE;
			}
			return "<html></html>";
		};
	}

	@Test
	void returnsCreatorsFromBothPlatformsThatHaveAPublicEmail() {
		List<RelayScoutResultResponse> results = service(working())
				.scout("dog training", List.of("youtube", "instagram"), List.of());

		assertEquals(2, results.size());
		RelayScoutResultResponse youtube = results.stream().filter(r -> r.plat().equals("youtube")).findFirst().orElseThrow();
		assertEquals("Hamilton Dog Training", youtube.name());
		assertEquals("@hamiltondogtraining", youtube.handle());
		assertEquals("hi@hamilton.co", youtube.email());
		assertEquals("125k", youtube.followers());
		assertTrue(youtube.signal().startsWith("About: “"));

		RelayScoutResultResponse instagram = results.stream().filter(r -> r.plat().equals("instagram")).findFirst().orElseThrow();
		assertEquals("@leesdogtraining", instagram.handle());
		assertEquals("leesdogtraining@aol.com", instagram.email());
		assertEquals("56", instagram.followers());
		assertEquals("instagram.com/leesdogtraining", instagram.url());
	}

	@Test
	void dropsCreatorsWithoutAPublicEmail() {
		CreatorPageFetcher noEmails = url -> {
			if (url.contains("youtube.com/results")) {
				return YoutubeCreatorParserTest.SEARCH_PAGE;
			}
			return "<html><script>var ytInitialData = {\"metadata\":{\"channelMetadataRenderer\":{\"description\":\"no contact\"}}};</script></html>";
		};

		assertTrue(service(noEmails).scout("dog training", List.of("youtube"), List.of()).isEmpty());
	}

	@Test
	void skipsCreatorsAlreadyOnTheUsersList() {
		List<RelayScoutResultResponse> results = service(working())
				.scout("dog training", List.of("instagram"), List.of("@LeesDogTraining"));

		assertTrue(results.isEmpty());
	}

	@Test
	void keepsWorkingWhenOnePlatformFails() {
		CreatorPageFetcher youtubeDown = url -> {
			if (url.contains("youtube.com")) {
				throw new IllegalStateException("blocked");
			}
			return YahooSearchParserTest.RESULTS_PAGE;
		};

		List<RelayScoutResultResponse> results = service(youtubeDown)
				.scout("dog training", List.of("youtube", "instagram"), List.of());

		assertEquals(1, results.size());
		assertEquals("instagram", results.get(0).plat());
	}

	@Test
	void reportsAnErrorWhenEveryPlatformFails() {
		CreatorPageFetcher down = url -> {
			throw new IllegalStateException("blocked");
		};

		assertThrows(BadGatewayException.class, () -> service(down).scout("dog training", List.of(), List.of()));
	}

	@Test
	void requiresANiche() {
		assertThrows(BusinessRuleException.class, () -> service(working()).scout("  ", List.of(), List.of()));
	}

	@Test
	void reusesRecentResultsForTheSameSearch() {
		RelayCreatorScoutService service = service(working());
		service.scout("dog training", List.of("instagram"), List.of());
		int firstRun = fetched.size();

		service.scout("Dog Training", List.of("instagram"), List.of());

		assertEquals(firstRun, fetched.size());
	}
}