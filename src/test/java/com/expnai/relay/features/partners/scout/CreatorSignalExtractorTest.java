package com.expnai.relay.features.partners.scout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CreatorSignalExtractorTest {

	private final CreatorSignalExtractor extractor = new CreatorSignalExtractor();

	@Test
	void findsEmailGluedToLabel() {
		assertEquals("leesdogtraining@aol.com",
				extractor.findEmail("Mobile dog training. Email:Leesdogtraining@aol.com ☎️").orElseThrow());
	}

	@Test
	void ignoresImageFileNamesAndMissingEmails() {
		assertTrue(extractor.findEmail("logo@2x.png and nothing else").isEmpty());
		assertTrue(extractor.findEmail("no contact here", null).isEmpty());
	}

	@Test
	void usesTheFirstTextThatHasAnEmail() {
		assertEquals("b@example.org", extractor.findEmail("none", "reach me: b@example.org").orElseThrow());
	}

	@Test
	void detectsEmailListMentionAndSize() {
		String bio = "Join 11k subscribers on my Sunday newsletter (beehiiv link below)";
		assertTrue(extractor.listSignal("Bio", bio).orElseThrow().startsWith("Bio: “"));
		assertEquals("11,000", extractor.listSize(bio));
	}

	@Test
	void listSizeIsNotStatedWithoutAListMention() {
		assertEquals("not stated", extractor.listSize("I have 125K subscribers on YouTube"));
		assertTrue(extractor.listSignal("About", "Just dog training tips").isEmpty());
	}

	@Test
	void describesProducts() {
		assertEquals("Sells something (gumroad link)", extractor.productNote("Templates at gumroad.com/x"));
		assertEquals("Mentions a course", extractor.productNote("Take my course"));
		assertEquals("No paid product found", extractor.productNote("Dog lover"));
	}
}