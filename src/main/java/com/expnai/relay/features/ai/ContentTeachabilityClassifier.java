package com.expnai.relay.features.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.expnai.relay.domain.entity.RelayPassage;
import com.expnai.relay.domain.entity.RelayProduct;
import com.expnai.relay.domain.entity.RelaySource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ContentTeachabilityClassifier {

	private static final Logger log = LoggerFactory.getLogger(ContentTeachabilityClassifier.class);
	private static final String GEMINI_CLASSIFIER_MODEL = "gemini-3.8-flash";
	private static final String OPENAI_CLASSIFIER_MODEL = "gpt-4o-mini";
	private static final int SAMPLE_CHAR_LIMIT = 10_000;
	private static final double MIN_BLOCKING_CONFIDENCE = 0.6;

	private final ObjectMapper objectMapper;

	public record ClassificationResult(
			boolean teachable,
			String contentType,
			String reason,
			double confidence,
			boolean nonTeachable
	) {
		public static ClassificationResult fallbackTeachable() {
			return new ClassificationResult(true, "unknown", null, 0.0, false);
		}
	}

	public ClassificationResult classify(
			String apiKey,
			RelayProduct product,
			List<RelaySource> sources,
			List<RelayPassage> passages) {
		if (apiKey == null || apiKey.isBlank()) {
			return ClassificationResult.fallbackTeachable();
		}
		try {
			String prompt = buildPrompt(product, sources, passages);
			String rawJson;
			if (apiKey.trim().startsWith("sk-")) {
				rawJson = callOpenAiCompatible(apiKey.trim(), prompt);
			}
			else {
				rawJson = callGemini(apiKey.trim(), prompt);
			}
			return parseResponse(rawJson);
		}
		catch (Exception ex) {
			log.warn("Content teachability classifier failed for product {} (falling back to thin-only gate): {}",
					product != null ? product.getId() : null, ex.getMessage());
			return ClassificationResult.fallbackTeachable();
		}
	}

	private String buildPrompt(
			RelayProduct product,
			List<RelaySource> sources,
			List<RelayPassage> passages) {
		StringBuilder sb = new StringBuilder();
		sb.append("""
				You are a source quality classifier for Relay, a platform that turns a creator's teaching material, guides, notes, transcripts, or domain knowledge into a paid digital product (guide, workbook, planner, or checklist).
				Determine whether the provided source sample contains teachable/instructional/informational domain material that can support a digital product, or if it is non-teachable (e.g., purely transactional records such as invoices, receipts, billing statements, tax forms, raw logs, or boilerplate with no transferable method or advice).
				Return ONLY valid JSON matching this exact schema:
				{
				  "teachable": true,
				  "contentType": "instructional|transactional|mixed|other",
				  "reason": "One short sentence explaining why the sources are or are not teachable.",
				  "confidence": 0.9
				}
				""");

		if (product != null && product.getName() != null && !product.getName().isBlank()) {
			sb.append("\nProduct name: ").append(product.getName().trim()).append("\n");
		}
		if (product != null && product.getInput() != null && !product.getInput().isBlank()) {
			sb.append("\nUser's stated subject / input: ").append(product.getInput().trim()).append("\n");
		}

		if (sources != null && !sources.isEmpty()) {
			sb.append("\nSource names:\n");
			for (RelaySource s : sources) {
				if (s.getName() != null && !s.getName().isBlank()) {
					sb.append("- ").append(s.getName().trim()).append("\n");
				}
			}
		}

		sb.append("\nSample of indexed passages:\n");
		StringBuilder sample = new StringBuilder();
		if (passages != null) {
			int idx = 1;
			for (RelayPassage p : passages) {
				if (p.getText() == null || p.getText().isBlank()) {
					continue;
				}
				String chunk = "--- Passage " + (idx++) + " ---\n" + p.getText().trim() + "\n\n";
				if (sample.length() + chunk.length() > SAMPLE_CHAR_LIMIT) {
					int remaining = SAMPLE_CHAR_LIMIT - sample.length();
					if (remaining > 80) {
						sample.append(chunk, 0, remaining);
					}
					break;
				}
				sample.append(chunk);
			}
		}
		sb.append(sample);
		return sb.toString();
	}

	private String callGemini(String apiKey, String prompt) {
		Client client = Client.builder().apiKey(apiKey).build();
		GenerateContentConfig config = GenerateContentConfig.builder()
				.responseMimeType("application/json")
				.candidateCount(1)
				.build();
		GenerateContentResponse response = client.models.generateContent(GEMINI_CLASSIFIER_MODEL, prompt, config);
		String text = response.text();
		if (text == null || text.isBlank()) {
			throw new IllegalStateException("Empty classifier response from Gemini");
		}
		return stripMarkdownFences(text);
	}

	private String callOpenAiCompatible(String apiKey, String prompt) throws Exception {
		HttpClient httpClient = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(15))
				.build();
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("model", OPENAI_CLASSIFIER_MODEL);
		payload.put("response_format", Map.of("type", "json_object"));
		payload.put("messages", List.of(
				Map.of("role", "system", "content", "You are a JSON classifier. Respond only with valid JSON."),
				Map.of("role", "user", "content", prompt)));

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("https://api.openai.com/v1/chat/completions"))
				.timeout(Duration.ofSeconds(30))
				.header("Authorization", "Bearer " + apiKey)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(
						objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
				.build();

		HttpResponse<String> response = httpClient.send(
				request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (response.statusCode() >= 400) {
			throw new IllegalStateException("Classifier HTTP " + response.statusCode());
		}
		JsonNode root = objectMapper.readTree(response.body());
		String content = root.path("choices").path(0).path("message").path("content").asText("");
		if (content.isBlank()) {
			throw new IllegalStateException("Empty classifier response from OpenAI");
		}
		return stripMarkdownFences(content);
	}

	private ClassificationResult parseResponse(String rawJson) throws Exception {
		JsonNode node = objectMapper.readTree(rawJson);
		boolean teachable = node.path("teachable").asBoolean(true);
		String contentType = node.path("contentType").asText("other").trim().toLowerCase(Locale.ROOT);
		String reason = node.path("reason").asText("").trim();
		double confidence = node.path("confidence").asDouble(0.0);
		boolean nonTeachable = !teachable && confidence >= MIN_BLOCKING_CONFIDENCE;
		return new ClassificationResult(teachable, contentType, reason, confidence, nonTeachable);
	}

	private String stripMarkdownFences(String text) {
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

