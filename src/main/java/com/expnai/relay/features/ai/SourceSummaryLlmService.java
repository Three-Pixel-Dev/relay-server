package com.expnai.relay.features.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.expnai.relay.common.exception.BusinessRuleException;
import com.expnai.relay.domain.entity.RelayProduct;
import com.expnai.relay.features.retrieval.RetrievedPassage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SourceSummaryLlmService {

	private static final Logger log = LoggerFactory.getLogger(SourceSummaryLlmService.class);
	private static final String GEMINI_SUMMARY_MODEL = "gemini-3.8-flash";
	private static final String OPENAI_SUMMARY_MODEL = "gpt-4o-mini";

	private final ObjectMapper objectMapper;
	private final AiUsageMeter aiUsageMeter;

	public record ThemeItem(String t, String d, String level, Integer hits) {
		public ThemeItem(String t, String d, String level) {
			this(t, d, level, null);
		}
	}

	public record GeneratedSourceProfile(
			String niche,
			String transformation,
			List<ThemeItem> themes,
			String verdict,
			String audience,
			String voice
	) {
	}

	public GeneratedSourceProfile summarizePassages(
			String apiKey,
			RelayProduct product,
			List<RetrievedPassage> passages) {
		int fallbackCount = passages != null ? passages.size() : 0;
		return summarizePassages(apiKey, product, passages, fallbackCount);
	}

	public GeneratedSourceProfile summarizePassages(
			String apiKey,
			RelayProduct product,
			List<RetrievedPassage> passages,
			int totalPassages) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new BusinessRuleException("Missing Text model API key for source summary.");
		}
		Long productId = product != null ? product.getId() : null;
		String prompt = buildPrompt(product, passages, totalPassages);
		String rawJson;
		if (apiKey.trim().startsWith("sk-")) {
			rawJson = callOpenAiCompatible(apiKey.trim(), prompt, productId);
		}
		else {
			rawJson = callGemini(apiKey.trim(), prompt, productId);
		}
		return parseResponse(rawJson);
	}

	private String buildPrompt(RelayProduct product, List<RetrievedPassage> passages, int totalPassages) {
		StringBuilder sb = new StringBuilder();
		sb.append("""
				You are Relay, a digital product strategist and editorial analyst.
				Read the retrieved source passages below and produce a structured JSON summary of what the sources teach.
				IMPORTANT RULES:
				1. Uploaded documents ([UPLOADED DOCUMENT]) take strict priority over what is inferred from public links ([YOUTUBE], [WEB]).
				2. Return ONLY valid JSON matching this exact schema:
				{
				  "niche": "Short, concrete niche label (e.g. 'Calm walking for reactive rescue dogs')",
				  "transformation": "One sentence describing the practical result/transformation for the reader",
				  "themes": [
				    {
				      "t": "Theme title (3-6 words)",
				      "d": "1-2 sentences explaining the evidence in the sources and how it maps to a paid digital product"
				    },
				    {
				      "t": "Second theme title",
				      "d": "1-2 sentences explaining the evidence and product fit"
				    },
				    {
				      "t": "Third theme title",
				      "d": "1-2 sentences explaining the evidence and product fit"
				    }
				  ],
				  "verdict": "One sentence naming the clearest paid digital product (guide, workbook, planner, or checklist) to build from these sources",
				  "audience": "One concise sentence describing who this is for",
				  "voice": "1-2 sentences describing the author/creator's tone, style, and how they teach"
				}
				3. Provide 3 distinct themes grounded in the retrieved passages. Evidence confidence levels will be computed separately from retrieval hit counts.
				4. For "niche", always describe the teachable subject or topic — NEVER use the uploaded file's filename or a document type label (such as "Invoice", "Receipt", "Statement", or a file name) as the niche. If the sources do not contain a clear teachable topic, return "" for "niche".
				""");
		sb.append("\nTotal indexed passages across sources: ").append(Math.max(0, totalPassages)).append("\n");

		if (product.getName() != null && !product.getName().isBlank()) {
			sb.append("\nProduct name: ").append(product.getName().trim()).append("\n");
		}
		if (product.getInput() != null && !product.getInput().isBlank()) {
			sb.append("\nUser's stated subject / input: ").append(product.getInput().trim()).append("\n");
		}
		if (product.isForElse() && product.getClientName() != null && !product.getClientName().isBlank()) {
			sb.append("Building in agency mode for creator/client: ").append(product.getClientName().trim()).append("\n");
		}
		if (product.getStepNote() != null && !product.getStepNote().isBlank()) {
			sb.append("User's requested adjustment / suggestion (MUST incorporate this): ")
					.append(product.getStepNote().trim()).append("\n");
		}

		sb.append("\nRetrieved Passages:\n");
		int idx = 1;
		for (RetrievedPassage p : passages) {
			String tag = "FILE".equalsIgnoreCase(p.sourceKind()) ? "[UPLOADED DOCUMENT]" : "[" + p.sourceKind() + "]";
			sb.append("--- Passage ").append(idx++).append(" ").append(tag)
					.append(" (").append(p.locator() != null ? p.locator() : p.sourceName()).append(") ---\n")
					.append(p.text()).append("\n\n");
		}
		return sb.toString();
	}

	private String callGemini(String apiKey, String prompt, Long productId) {
		try {
			Client client = Client.builder().apiKey(apiKey).build();
			GenerateContentConfig config = GenerateContentConfig.builder()
					.responseMimeType("application/json")
					.candidateCount(1)
					.build();
			GenerateContentResponse response = client.models.generateContent(GEMINI_SUMMARY_MODEL, prompt, config);
			String text = response.text();
			aiUsageMeter.recordGeminiChat(productId, 0, GEMINI_SUMMARY_MODEL, response, prompt, text, "summary");
			if (text == null || text.isBlank()) {
				throw new BusinessRuleException("Gemini returned an empty summary response.");
			}
			return stripMarkdownFences(text);
		}
		catch (BusinessRuleException ex) {
			throw ex;
		}
		catch (Exception ex) {
			log.error("Gemini summary generation failed: {}", ex.getMessage());
			throw new BusinessRuleException("Gemini summary generation failed: " + ex.getMessage());
		}
	}

	private String callOpenAiCompatible(String apiKey, String prompt, Long productId) {
		try {
			HttpClient httpClient = HttpClient.newBuilder()
					.connectTimeout(Duration.ofSeconds(20))
					.build();
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("model", OPENAI_SUMMARY_MODEL);
			payload.put("response_format", Map.of("type", "json_object"));
			payload.put("messages", List.of(
					Map.of("role", "system", "content", "You are Relay. Respond only with valid JSON."),
					Map.of("role", "user", "content", prompt)));

			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create("https://api.openai.com/v1/chat/completions"))
					.timeout(Duration.ofSeconds(60))
					.header("Authorization", "Bearer " + apiKey)
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
					.build();

			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() >= 400) {
				throw new BusinessRuleException("Text model returned HTTP " + response.statusCode() + ": " + response.body());
			}
			JsonNode root = objectMapper.readTree(response.body());
			String content = root.path("choices").path(0).path("message").path("content").asText("");
			aiUsageMeter.recordOpenAiChat(productId, 0, OPENAI_SUMMARY_MODEL, root, prompt, content, "summary");
			if (content.isBlank()) {
				throw new BusinessRuleException("Text model returned an empty summary response.");
			}
			return stripMarkdownFences(content);
		}
		catch (BusinessRuleException ex) {
			throw ex;
		}
		catch (Exception ex) {
			log.error("OpenAI summary generation failed: {}", ex.getMessage());
			throw new BusinessRuleException("Text model summary generation failed: " + ex.getMessage());
		}
	}

	private GeneratedSourceProfile parseResponse(String rawJson) {
		try {
			JsonNode node = objectMapper.readTree(rawJson);
			String niche = textOrDefault(node, "niche", "");
			String transformation = textOrDefault(node, "transformation", "Helps readers apply a clear, repeatable method.");
			String verdict = textOrDefault(node, "verdict", "A practical step-by-step guide or workbook built from the core material.");
			String audience = textOrDefault(node, "audience", "Readers looking for a structured, actionable approach.");
			String voice = textOrDefault(node, "voice", "Clear, direct, and practical.");

			List<ThemeItem> themes = new ArrayList<>();
			JsonNode themesNode = node.path("themes");
			if (themesNode.isArray()) {
				for (JsonNode tNode : themesNode) {
					String t = textOrDefault(tNode, "t", "Core theme");
					String d = textOrDefault(tNode, "d", "Supported by passages in the provided sources.");
					themes.add(new ThemeItem(t, d, null));
				}
			}
			if (themes.isEmpty()) {
				themes.add(new ThemeItem(
						"Core method",
						"Primary actionable framework found in the sources.",
						null));
			}
			return new GeneratedSourceProfile(niche, transformation, themes, verdict, audience, voice);
		}
		catch (Exception ex) {
			throw new BusinessRuleException("Failed to parse LLM source profile JSON: " + ex.getMessage());
		}
	}

	private String textOrDefault(JsonNode node, String field, String fallback) {
		String val = node.path(field).asText("").trim();
		return val.isEmpty() ? fallback : val;
	}

	/**
	 * Maps per-theme RAG retrieval hit count (passages with cosine similarity >= 0.35) to evidence level:
	 * 0–1 hits -> "Medium"
	 * 2–3 hits -> "Medium-high"
	 * 4+ hits -> "High"
	 */
	public static String levelFromHits(int hits) {
		if (hits <= 1) {
			return "Medium";
		}
		if (hits <= 3) {
			return "Medium-high";
		}
		return "High";
	}

	/**
	 * Hard-caps evidence levels by total indexed passage count:
	 * 1–4 passages -> max "Medium"
	 * 5–19 passages -> max "Medium-high"
	 * 20+ passages -> "High" allowed
	 */
	public static String clampLevel(String normalizedLevel, int totalPassages) {
		if (totalPassages <= 4) {
			return "Medium";
		}
		if (totalPassages < 20) {
			return "High".equals(normalizedLevel) ? "Medium-high" : normalizedLevel;
		}
		return normalizedLevel;
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

