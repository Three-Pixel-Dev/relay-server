package com.expnai.relay.features.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.expnai.relay.features.product.ingest.FfmpegAudioService.SampledFrame;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;

import lombok.RequiredArgsConstructor;

/**
 * Combines a speech transcript with unique video scenes using {@code gemini-3.8-flash}.
 * Metered as {@code source_video_scenes}.
 */
@Service
@RequiredArgsConstructor
public class GeminiVideoNotesClient {

	private static final Logger log = LoggerFactory.getLogger(GeminiVideoNotesClient.class);
	private static final String MODEL_ID = "gemini-3.8-flash";

	private final AiUsageMeter aiUsageMeter;

	public String combineTranscriptAndScenes(
			String apiKey,
			String transcript,
			List<SceneImage> scenes,
			Long productId,
			String sourceName) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new IllegalArgumentException(
					"Connect a Google Gemini API key (AIza…) to read video scenes.");
		}
		String trimmedKey = apiKey.trim();
		if (trimmedKey.startsWith("sk-")) {
			throw new IllegalArgumentException(
					"Video scene reading requires a Google Gemini API key (AIza…), not an OpenAI key.");
		}
		if (scenes == null || scenes.size() < 2) {
			return transcript;
		}

		String prompt = buildPrompt(transcript, scenes, sourceName);
		List<Part> parts = new ArrayList<>();
		parts.add(Part.fromText(prompt));
		for (SceneImage scene : scenes) {
			if (scene.jpegBytes() != null && scene.jpegBytes().length > 0) {
				parts.add(Part.fromBytes(scene.jpegBytes(), "image/jpeg"));
			}
		}
		if (parts.size() < 3) {
			return transcript;
		}

		Client client = Client.builder().apiKey(trimmedKey).build();
		Content content = Content.fromParts(parts.toArray(Part[]::new));
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
				prompt,
				rawText != null ? rawText : "",
				"source_video_scenes");

		if (rawText == null || rawText.isBlank()) {
			log.debug("Gemini video notes empty for {} (product={})", sourceName, productId);
			return transcript;
		}
		String notes = stripOuterCodeFences(rawText);
		return notes.length() >= 40 ? notes : transcript;
	}

	public static SceneImage fromFrame(SampledFrame frame, byte[] jpegBytes) {
		return new SceneImage(jpegBytes, frame.timeSeconds());
	}

	public record SceneImage(byte[] jpegBytes, double timeSeconds) {
	}

	private String buildPrompt(String transcript, List<SceneImage> scenes, String sourceName) {
		StringBuilder sceneTimes = new StringBuilder();
		for (int i = 0; i < scenes.size(); i++) {
			sceneTimes.append("- Image ").append(i + 1)
					.append(" is scene ").append(i + 1)
					.append(" at ").append(formatTime(scenes.get(i).timeSeconds()))
					.append('\n');
		}
		return """
				You are indexing a video source for a knowledge product.
				File: %s

				The speech transcript is below. The attached images are unique on-screen scenes in time order.
				%s
				Write ONE plain-text document that:
				- Keeps the full meaning of the speech (do not drop important steps).
				- After each stretch of talk that matches a scene, insert a short block:
				  [Scene N, mm:ss] then the on-screen text and what is shown.
				- Do not add a title, summary, translation, or extra commentary.
				Output ONLY the document.

				Transcript:
				%s
				""".formatted(sourceName, sceneTimes, transcript != null ? transcript : "");
	}

	private String formatTime(double seconds) {
		int total = Math.max(0, (int) Math.round(seconds));
		int mins = total / 60;
		int secs = total % 60;
		return String.format(Locale.ROOT, "%d:%02d", mins, secs);
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
