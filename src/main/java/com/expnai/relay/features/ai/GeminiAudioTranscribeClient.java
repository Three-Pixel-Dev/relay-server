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
 * Transcribes source audio with {@code gemini-3.8-flash} using the user's Gemini chat key.
 * Usage is metered as {@code source_audio_transcribe}.
 */
@Service
@RequiredArgsConstructor
public class GeminiAudioTranscribeClient {

	private static final Logger log = LoggerFactory.getLogger(GeminiAudioTranscribeClient.class);
	private static final String MODEL_ID = "gemini-3.8-flash";

	private static final String TRANSCRIBE_PROMPT = """
			Transcribe this audio into plain text in the original spoken language.
			Keep natural paragraphs. If speakers change, start a new paragraph.
			Do not add a title, summary, translation, or commentary.
			Output ONLY the transcript. If there is no speech, return an empty string.
			""";

	private final AiUsageMeter aiUsageMeter;

	public String transcribeWav(String apiKey, byte[] wavBytes, Long productId, String sourceName) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new IllegalArgumentException(
					"Connect a Google Gemini API key (AIza…) to transcribe audio sources.");
		}
		String trimmedKey = apiKey.trim();
		if (trimmedKey.startsWith("sk-")) {
			throw new IllegalArgumentException(
					"Audio transcription requires a Google Gemini API key (AIza…), not an OpenAI key.");
		}
		if (wavBytes == null || wavBytes.length == 0) {
			return "";
		}

		Client client = Client.builder().apiKey(trimmedKey).build();
		Content content = Content.fromParts(
				Part.fromBytes(wavBytes, "audio/wav"),
				Part.fromText(TRANSCRIBE_PROMPT));
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
				TRANSCRIBE_PROMPT,
				rawText != null ? rawText : "",
				"source_audio_transcribe");

		if (rawText == null || rawText.isBlank()) {
			log.debug("Gemini transcription empty for {} (product={})", sourceName, productId);
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
