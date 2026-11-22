package com.expnai.relay.features.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;

import lombok.RequiredArgsConstructor;

/**
 * Multimodal OCR client for scanned/image-only PDF pages using {@code gemini-3.8-flash}.
 * Uses the user's existing Gemini chat API key and meters usage as {@code source_pdf_ocr}.
 */
@Service
@RequiredArgsConstructor
public class GeminiPdfOcrClient {

	private static final Logger log = LoggerFactory.getLogger(GeminiPdfOcrClient.class);
	private static final String MODEL_ID = "gemini-3.8-flash";

	private static final String OCR_PROMPT = """
			Extract all readable text from this PDF page image in natural reading order.
			Preserve headings, paragraphs, bullet points, and line breaks.
			Output ONLY the extracted plain text with no commentary, preamble, or markdown code fences.
			If the page contains no readable text, return an empty string.
			""";

	private final AiUsageMeter aiUsageMeter;

	public String extractPageText(String apiKey, byte[] pngBytes, int pageNumber, Long productId) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new IllegalArgumentException(
					"Connect a Google Gemini API key (AIza…) to OCR scanned PDF pages.");
		}
		String trimmedKey = apiKey.trim();
		if (trimmedKey.startsWith("sk-")) {
			throw new IllegalArgumentException(
					"Scanned PDF OCR requires a Google Gemini API key (AIza…), not an OpenAI key.");
		}
		if (pngBytes == null || pngBytes.length == 0) {
			return "";
		}

		Client client = Client.builder().apiKey(trimmedKey).build();
		Content content = Content.fromParts(
				Part.fromBytes(pngBytes, "image/png"),
				Part.fromText(OCR_PROMPT));
		GenerateContentConfig config = GenerateContentConfig.builder()
				.candidateCount(1)
				.build();

		GenerateContentResponse response = client.models.generateContent(MODEL_ID, content, config);
		String rawText = response.text();
		aiUsageMeter.recordGeminiChat(
				productId,
				0,
				MODEL_ID,
				response,
				OCR_PROMPT,
				rawText != null ? rawText : "",
				"source_pdf_ocr");

		if (rawText == null || rawText.isBlank()) {
			log.debug("Gemini OCR returned empty text for page {} (product={})", pageNumber, productId);
			return "";
		}
		return stripOuterCodeFences(rawText);
	}

	private String stripOuterCodeFences(String text) {
		String trimmed = text.trim();
		if (trimmed.startsWith("```")) {
			int firstNewline = trimmed.indexOf('\n');
			if (firstNewline > 0) {
				trimmed = trimmed.substring(firstNewline + 1);
			}
			if (trimmed.endsWith("```")) {
				trimmed = trimmed.substring(0, trimmed.length() - 3);
			}
		}
		return trimmed.trim();
	}
}

