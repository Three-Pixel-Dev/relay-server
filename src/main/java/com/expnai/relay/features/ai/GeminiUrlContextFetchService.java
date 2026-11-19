package com.expnai.relay.features.ai;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.google.genai.Client;
import com.google.genai.types.Candidate;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Tool;
import com.google.genai.types.UrlContext;
import com.google.genai.types.UrlContextMetadata;
import com.google.genai.types.UrlMetadata;
import com.google.genai.types.UrlRetrievalStatus;

/**
 * Fallback web page text extractor using Gemini's built-in {@code url_context} tool
 * when Playwright Chromium cannot load or extract readable text from a public URL.
 */
@Service
public class GeminiUrlContextFetchService {

	private static final Logger log = LoggerFactory.getLogger(GeminiUrlContextFetchService.class);
	private static final String GEMINI_URL_CONTEXT_MODEL = "gemini-3.8-flash";
	private static final String UNREADABLE_PREFIX = "UNREADABLE_URL:";
	private static final int MIN_EXTRACTED_CHARS = 40;

	public String extractPageText(String geminiApiKey, String url) {
		if (geminiApiKey == null || geminiApiKey.isBlank()) {
			throw new IllegalArgumentException("Missing Gemini API key for URL Context fallback.");
		}
		String trimmedKey = geminiApiKey.trim();
		if (trimmedKey.startsWith("sk-")) {
			throw new IllegalArgumentException("Gemini URL Context requires a Google Gemini API key (AIza…).");
		}

		log.info("Using Gemini URL Context fallback to read web page: {}", url);

		try {
			Client client = Client.builder().apiKey(trimmedKey).build();
			Tool urlTool = Tool.builder()
					.urlContext(UrlContext.builder().build())
					.build();
			GenerateContentConfig config = GenerateContentConfig.builder()
					.tools(List.of(urlTool))
					.candidateCount(1)
					.build();

			String prompt = buildExtractionPrompt(url);
			GenerateContentResponse response = client.models.generateContent(
					GEMINI_URL_CONTEXT_MODEL,
					prompt,
					config);

			verifyUrlRetrievalMetadata(response, url);

			String text = response.text();
			if (text == null || text.isBlank()) {
				throw new IllegalArgumentException("Gemini URL Context returned an empty response for " + url);
			}

			String cleaned = text.trim();
			if (cleaned.toUpperCase(Locale.ROOT).startsWith(UNREADABLE_PREFIX)) {
				String reason = cleaned.substring(UNREADABLE_PREFIX.length()).trim();
				throw new IllegalArgumentException(
						reason.isEmpty() ? "Gemini could not access the URL content." : reason);
			}

			if (cleaned.length() < MIN_EXTRACTED_CHARS || looksLikeInaccessibleRefusal(cleaned)) {
				throw new IllegalArgumentException("Gemini URL Context could not extract readable body text from " + url);
			}

			return cleaned;
		}
		catch (IllegalArgumentException ex) {
			throw ex;
		}
		catch (Exception ex) {
			log.warn("Gemini URL Context fallback failed for {}: {}", url, ex.getMessage());
			throw new IllegalArgumentException("Gemini URL read failed: " + ex.getMessage(), ex);
		}
	}

	private String buildExtractionPrompt(String url) {
		return """
				Fetch and read the following URL using your url_context tool:
				%s

				TASK:
				Extract the main readable content of that page as plain text (include the page title, headings, paragraphs, bullet points, and substantive details as completely and faithfully as possible).

				STRICT RULES:
				1. Do NOT write a short summary and do NOT invent or guess content from the URL string alone.
				2. Omit navigation menus, cookie notices, and footer boilerplate.
				3. If the URL cannot be fetched, is behind a login/paywall, or returns an error/empty page, reply ONLY with:
				UNREADABLE_URL: <one short sentence explaining why>
				""".formatted(url);
	}

	private void verifyUrlRetrievalMetadata(GenerateContentResponse response, String url) {
		Optional<List<Candidate>> candidatesOpt = response.candidates();
		if (candidatesOpt.isEmpty() || candidatesOpt.get().isEmpty()) {
			return;
		}
		Candidate candidate = candidatesOpt.get().get(0);
		Optional<UrlContextMetadata> metaOpt = candidate.urlContextMetadata();
		if (metaOpt.isEmpty()) {
			return;
		}
		Optional<List<UrlMetadata>> urlsOpt = metaOpt.get().urlMetadata();
		if (urlsOpt.isEmpty()) {
			return;
		}
		for (UrlMetadata item : urlsOpt.get()) {
			Optional<UrlRetrievalStatus> statusOpt = item.urlRetrievalStatus();
			if (statusOpt.isPresent()) {
				UrlRetrievalStatus.Known known = statusOpt.get().knownEnum();
				if (known == UrlRetrievalStatus.Known.URL_RETRIEVAL_STATUS_PAYWALL) {
					throw new IllegalArgumentException("Page appears to be behind a paywall or login: " + url);
				}
				if (known == UrlRetrievalStatus.Known.URL_RETRIEVAL_STATUS_UNSAFE) {
					throw new IllegalArgumentException("Page was flagged as unsafe to retrieve: " + url);
				}
				if (known == UrlRetrievalStatus.Known.URL_RETRIEVAL_STATUS_ERROR) {
					throw new IllegalArgumentException("Gemini URL fetcher could not reach " + url);
				}
			}
		}
	}

	private boolean looksLikeInaccessibleRefusal(String text) {
		if (text.length() > 350) {
			return false;
		}
		String lower = text.toLowerCase(Locale.ROOT);
		return lower.contains("unable to access")
				|| lower.contains("cannot access")
				|| lower.contains("could not access")
				|| lower.contains("unable to retrieve")
				|| lower.contains("cannot retrieve")
				|| lower.contains("could not fetch");
	}
}

