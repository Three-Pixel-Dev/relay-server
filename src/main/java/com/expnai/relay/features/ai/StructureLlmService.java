package com.expnai.relay.features.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.expnai.relay.common.exception.BusinessRuleException;
import com.expnai.relay.domain.entity.RelayPocket;
import com.expnai.relay.domain.entity.RelayProduct;
import com.expnai.relay.domain.entity.RelaySourceProfile;
import com.expnai.relay.domain.enums.RelayProductType;
import com.expnai.relay.features.retrieval.RetrievedPassage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StructureLlmService {

	private static final Logger log = LoggerFactory.getLogger(StructureLlmService.class);
	private static final String GEMINI_MODEL = "gemini-3.8-flash";
	private static final String OPENAI_MODEL = "gpt-4o-mini";
	private static final int MIN_SECTIONS = 3;
	private static final int MAX_SECTIONS = 14;

	private static final Map<RelayProductType, Integer> TARGET_SECTION_COUNTS = new EnumMap<>(RelayProductType.class);

	static {
		TARGET_SECTION_COUNTS.put(RelayProductType.EBOOK, 11);
		TARGET_SECTION_COUNTS.put(RelayProductType.WORKBOOK, 9);
		TARGET_SECTION_COUNTS.put(RelayProductType.PLANNER, 8);
		TARGET_SECTION_COUNTS.put(RelayProductType.CHECKLIST, 4);
		TARGET_SECTION_COUNTS.put(RelayProductType.TEMPLATES, 8);
		TARGET_SECTION_COUNTS.put(RelayProductType.MINICOURSE, 7);
	}

	private final ObjectMapper objectMapper;
	private final AiUsageMeter aiUsageMeter;

	public record GeneratedSection(String title, String moment) {
	}

	public static int targetSectionCount(RelayProductType type) {
		if (type == null) {
			return 9;
		}
		return TARGET_SECTION_COUNTS.getOrDefault(type, 9);
	}

	public List<GeneratedSection> generateStructure(
			String apiKey,
			RelayProduct product,
			RelayPocket pocket,
			RelaySourceProfile profile,
			List<RetrievedPassage> passages) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new BusinessRuleException("Missing Text model API key for structure generation.");
		}
		Long productId = product != null ? product.getId() : null;
		int target = targetSectionCount(product != null ? product.getProductType() : null);
		String prompt = buildPrompt(product, pocket, profile, passages, target);
		String rawJson = callLlmJson(apiKey.trim(), prompt, productId);
		return parseResponse(rawJson, target);
	}

	private String buildPrompt(
			RelayProduct product,
			RelayPocket pocket,
			RelaySourceProfile profile,
			List<RetrievedPassage> passages,
			int targetCount) {
		String typeLabel = product != null && product.getProductType() != null
				? product.getProductType().getCode().toLowerCase()
				: "workbook";

		StringBuilder sb = new StringBuilder();
		sb.append("""
				You are Relay, a digital product editorial architect.
				Design a clear, practical table of contents (list of sections) for this digital product.
				Each section MUST own one concrete, real-world moment the reader actually faces (not vague textbook chapter names).

				STRICT RULES:
				1. Return ONLY valid JSON matching this exact schema:
				{
				  "sections": [
				    {
				      "title": "Short, concrete section title (3-8 words)",
				      "moment": "One specific sentence naming the exact moment the reader faces when they open this section"
				    }
				  ]
				}
				2. Target count: %d sections (you may use %d to %d sections if it fits the flow better).
				3. Order sections logically from first quick win / setup through core method, fill-in/action pages (for workbooks/planners/templates), and sustaining results.
				4. Ground section ideas in the chosen pocket, product type (%s), source profile, and retrieved source passages (if provided).
				""".formatted(
				targetCount,
				Math.max(MIN_SECTIONS, targetCount - 1),
				Math.min(MAX_SECTIONS, targetCount + 1),
				typeLabel));

		if (product != null) {
			if (product.getName() != null && !product.getName().isBlank()) {
				sb.append("\nProduct name: ").append(product.getName().trim()).append("\n");
			}
			if (product.getInput() != null && !product.getInput().isBlank()) {
				sb.append("User's subject / input: ").append(product.getInput().trim()).append("\n");
			}
			if (product.getStepNote() != null && !product.getStepNote().isBlank()) {
				sb.append("User's requested adjustment / suggestion (MUST incorporate this): ")
						.append(product.getStepNote().trim()).append("\n");
			}
		}

		if (pocket != null) {
			sb.append("\n=== CHOSEN POCKET ===\n");
			if (pocket.getHeadline() != null) {
				sb.append("Headline: ").append(pocket.getHeadline()).append("\n");
			}
			if (pocket.getNiche() != null) {
				sb.append("Niche: ").append(pocket.getNiche()).append("\n");
			}
			if (pocket.getProblem() != null) {
				sb.append("Buyer problem: ").append(pocket.getProblem()).append("\n");
			}
			if (pocket.getKnowledge() != null) {
				sb.append("Knowledge / method: ").append(pocket.getKnowledge()).append("\n");
			}
		}

		if (profile != null) {
			sb.append("\n=== SOURCE PROFILE ===\n");
			if (profile.getNiche() != null && !profile.getNiche().isBlank()) {
				sb.append("Source niche: ").append(profile.getNiche()).append("\n");
			}
			if (profile.getTransformation() != null && !profile.getTransformation().isBlank()) {
				sb.append("Transformation: ").append(profile.getTransformation()).append("\n");
			}
			if (profile.getAudience() != null && !profile.getAudience().isBlank()) {
				sb.append("Audience: ").append(profile.getAudience()).append("\n");
			}
			if (profile.getVoice() != null && !profile.getVoice().isBlank()) {
				sb.append("Voice: ").append(profile.getVoice()).append("\n");
			}
			if (profile.getThemesJson() != null && !profile.getThemesJson().isBlank()) {
				sb.append("Themes: ").append(profile.getThemesJson()).append("\n");
			}
		}

		if (passages != null && !passages.isEmpty()) {
			sb.append("\n=== RETRIEVED SOURCE PASSAGES ===\n");
			int idx = 1;
			for (RetrievedPassage p : passages) {
				String snippet = p.text() != null ? p.text().trim() : "";
				if (snippet.length() > 700) {
					snippet = snippet.substring(0, 700) + "…";
				}
				sb.append("--- Passage ").append(idx++)
						.append(" (").append(p.locator() != null ? p.locator() : p.sourceName()).append(") ---\n")
						.append(snippet).append("\n\n");
			}
		}

		return sb.toString();
	}

	private List<GeneratedSection> parseResponse(String rawJson, int targetCount) {
		try {
			JsonNode root = objectMapper.readTree(rawJson);
			JsonNode arr = root.has("sections") ? root.path("sections") : root;
			if (!arr.isArray() || arr.isEmpty()) {
				throw new BusinessRuleException("Model did not return a sections array.");
			}

			int maxAllowed = Math.min(MAX_SECTIONS, Math.max(MIN_SECTIONS, targetCount + 1));
			List<GeneratedSection> sections = new ArrayList<>();
			for (JsonNode node : arr) {
				if (sections.size() >= maxAllowed) {
					break;
				}
				String title = node.path("title").asText("").trim();
				String moment = node.path("moment").asText("").trim();
				if (title.isEmpty()) {
					continue;
				}
				if (moment.isEmpty()) {
					moment = "When the reader puts this part of the method into practice.";
				}
				sections.add(new GeneratedSection(title, moment));
			}

			if (sections.size() < MIN_SECTIONS) {
				throw new BusinessRuleException("Model returned too few sections (" + sections.size() + ").");
			}
			return sections;
		}
		catch (BusinessRuleException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new BusinessRuleException("Failed to parse structure JSON: " + ex.getMessage());
		}
	}

	private String callLlmJson(String apiKey, String prompt, Long productId) {
		if (apiKey.startsWith("sk-")) {
			return callOpenAiCompatible(apiKey, prompt, productId);
		}
		return callGemini(apiKey, prompt, productId);
	}

	private String callGemini(String apiKey, String prompt, Long productId) {
		try {
			Client client = Client.builder().apiKey(apiKey).build();
			GenerateContentConfig config = GenerateContentConfig.builder()
					.responseMimeType("application/json")
					.candidateCount(1)
					.build();
			GenerateContentResponse response = client.models.generateContent(GEMINI_MODEL, prompt, config);
			String text = response.text();
			aiUsageMeter.recordGeminiChat(productId, 3, GEMINI_MODEL, response, prompt, text, "structure");
			if (text == null || text.isBlank()) {
				throw new BusinessRuleException("Gemini returned an empty structure response.");
			}
			return stripMarkdownFences(text);
		}
		catch (BusinessRuleException ex) {
			throw ex;
		}
		catch (Exception ex) {
			log.error("Gemini structure generation failed: {}", ex.getMessage());
			throw new BusinessRuleException("Gemini structure generation failed: " + ex.getMessage());
		}
	}

	private String callOpenAiCompatible(String apiKey, String prompt, Long productId) {
		try {
			HttpClient httpClient = HttpClient.newBuilder()
					.connectTimeout(Duration.ofSeconds(20))
					.build();
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("model", OPENAI_MODEL);
			payload.put("response_format", Map.of("type", "json_object"));
			payload.put("messages", List.of(
					Map.of("role", "system", "content", "You are Relay. Respond only with valid JSON."),
					Map.of("role", "user", "content", prompt)));

			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create("https://api.openai.com/v1/chat/completions"))
					.timeout(Duration.ofSeconds(60))
					.header("Authorization", "Bearer " + apiKey)
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(
							objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
					.build();

			HttpResponse<String> response = httpClient.send(
					request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() >= 400) {
				throw new BusinessRuleException("Text model returned HTTP " + response.statusCode());
			}
			JsonNode root = objectMapper.readTree(response.body());
			String content = root.path("choices").path(0).path("message").path("content").asText("");
			aiUsageMeter.recordOpenAiChat(productId, 3, OPENAI_MODEL, root, prompt, content, "structure");
			if (content.isBlank()) {
				throw new BusinessRuleException("Text model returned an empty structure response.");
			}
			return stripMarkdownFences(content);
		}
		catch (BusinessRuleException ex) {
			throw ex;
		}
		catch (Exception ex) {
			log.error("OpenAI structure generation failed: {}", ex.getMessage());
			throw new BusinessRuleException("Text model structure generation failed: " + ex.getMessage());
		}
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

