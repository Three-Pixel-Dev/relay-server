package com.expnai.relay.features.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.expnai.relay.common.exception.BusinessRuleException;
import com.expnai.relay.domain.entity.RelayPocket;
import com.expnai.relay.domain.entity.RelayProduct;
import com.expnai.relay.domain.entity.RelaySection;
import com.expnai.relay.domain.entity.RelaySourceProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BrandingLlmService {

	private static final Logger log = LoggerFactory.getLogger(BrandingLlmService.class);
	private static final String GEMINI_MODEL = "gemini-3.8-flash";
	private static final String OPENAI_MODEL = "gpt-4o-mini";

	private static final String[] PALETTE_TYPE_PAIRS = {
			"Merriweather / Open Sans",
			"Fraunces / Inter",
			"Sora / Sora"
	};

	private final ObjectMapper objectMapper;
	private final AiUsageMeter aiUsageMeter;

	public record GeneratedBrand(String title, int palette, int cover, String typePair, String rationale) {
	}

	public static String typePairForPalette(int paletteIndex) {
		int idx = Math.max(0, Math.min(PALETTE_TYPE_PAIRS.length - 1, paletteIndex));
		return PALETTE_TYPE_PAIRS[idx];
	}

	public GeneratedBrand generateBrand(
			String apiKey,
			RelayProduct product,
			RelayPocket pocket,
			RelaySourceProfile profile,
			List<RelaySection> sections) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new BusinessRuleException("Missing Text model API key for branding generation.");
		}
		Long productId = product != null ? product.getId() : null;
		String prompt = buildPrompt(product, pocket, profile, sections);
		String rawJson = apiKey.trim().startsWith("sk-")
				? callOpenAiCompatible(apiKey.trim(), prompt, productId)
				: callGemini(apiKey.trim(), prompt, productId);
		return parseResponse(rawJson, product, pocket);
	}

	private String buildPrompt(
			RelayProduct product,
			RelayPocket pocket,
			RelaySourceProfile profile,
			List<RelaySection> sections) {
		StringBuilder sb = new StringBuilder();
		sb.append("""
				You are Relay, a digital product brand & cover designer.
				Choose a crisp, marketable cover title, color palette, and cover layout variant for this digital product.

				Available palettes (pick index 0, 1, or 2):
				- 0: Editorial (warm paper cream, charcoal ink, terracotta accent — Merriweather / Open Sans; best for guides, coaching, craft, lifestyle)
				- 1: Calm (soft off-white, deep navy, sage teal — Fraunces / Inter; best for wellness, parenting, pets, mindfulness, planners)
				- 2: Bold (bright ivory, deep ink, golden sun yellow — Sora / Sora; best for business, creators, productivity, templates, systems)

				Available cover layout variants (pick index 0, 1, or 2):
				- 0: Editorial band (light background, bold bottom accent band)
				- 1: Dark focal circle (dark ink background, glowing accent circle)
				- 2: Centered wave card (light background, centered title with calm wave motif)

				Return ONLY valid JSON matching this exact schema:
				{
				  "title": "Crisp cover title (3-7 words, punchy and readable on a book cover)",
				  "palette": 0,
				  "cover": 0,
				  "rationale": "One short sentence explaining why this title and palette fit the buyer."
				}
				""");

		if (product != null) {
			if (product.getName() != null && !product.getName().isBlank()) {
				sb.append("\nWorking product name: ").append(product.getName().trim()).append("\n");
			}
			if (product.getProductType() != null) {
				sb.append("Product type: ").append(product.getProductType().getCode().toLowerCase()).append("\n");
			}
			if (product.getStepNote() != null && !product.getStepNote().isBlank()) {
				sb.append("User's branding suggestion (MUST incorporate this): ")
						.append(product.getStepNote().trim()).append("\n");
			}
		}

		if (pocket != null) {
			sb.append("\n=== POCKET ===\n");
			if (pocket.getHeadline() != null) {
				sb.append("Headline: ").append(pocket.getHeadline()).append("\n");
			}
			if (pocket.getNiche() != null) {
				sb.append("Niche: ").append(pocket.getNiche()).append("\n");
			}
		}

		if (profile != null) {
			if (profile.getAudience() != null && !profile.getAudience().isBlank()) {
				sb.append("Audience: ").append(profile.getAudience()).append("\n");
			}
			if (profile.getVoice() != null && !profile.getVoice().isBlank()) {
				sb.append("Voice: ").append(profile.getVoice()).append("\n");
			}
		}

		if (sections != null && !sections.isEmpty()) {
			sb.append("\nSection titles:\n");
			for (RelaySection s : sections) {
				if (s.getTitle() != null && !s.getTitle().isBlank()) {
					sb.append("- ").append(s.getTitle()).append("\n");
				}
			}
		}

		return sb.toString();
	}

	private GeneratedBrand parseResponse(String rawJson, RelayProduct product, RelayPocket pocket) {
		try {
			JsonNode node = objectMapper.readTree(rawJson);
			String fallbackTitle = (product != null && product.getName() != null && !product.getName().isBlank())
					? product.getName().trim()
					: (pocket != null && pocket.getNiche() != null && !pocket.getNiche().isBlank())
							? pocket.getNiche().trim()
							: "Practical Field Guide";
			String title = node.path("title").asText("").trim();
			if (title.isEmpty()) {
				title = fallbackTitle;
			}
			int palette = Math.max(0, Math.min(2, node.path("palette").asInt(0)));
			int cover = Math.max(0, Math.min(2, node.path("cover").asInt(0)));
			String rationale = node.path("rationale").asText("").trim();
			return new GeneratedBrand(title, palette, cover, typePairForPalette(palette), rationale);
		}
		catch (Exception ex) {
			throw new BusinessRuleException("Failed to parse branding JSON: " + ex.getMessage());
		}
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
			aiUsageMeter.recordGeminiChat(productId, 5, GEMINI_MODEL, response, prompt, text, "branding");
			if (text == null || text.isBlank()) {
				throw new BusinessRuleException("Gemini returned an empty branding response.");
			}
			return stripMarkdownFences(text);
		}
		catch (BusinessRuleException ex) {
			throw ex;
		}
		catch (Exception ex) {
			log.error("Gemini branding generation failed: {}", ex.getMessage());
			throw new BusinessRuleException("Gemini branding generation failed: " + ex.getMessage());
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
					.timeout(Duration.ofSeconds(45))
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
			aiUsageMeter.recordOpenAiChat(productId, 5, OPENAI_MODEL, root, prompt, content, "branding");
			if (content.isBlank()) {
				throw new BusinessRuleException("Text model returned an empty branding response.");
			}
			return stripMarkdownFences(content);
		}
		catch (BusinessRuleException ex) {
			throw ex;
		}
		catch (Exception ex) {
			log.error("OpenAI branding generation failed: {}", ex.getMessage());
			throw new BusinessRuleException("Text model branding generation failed: " + ex.getMessage());
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

