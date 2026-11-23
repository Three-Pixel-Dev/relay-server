package com.expnai.relay.features.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.expnai.relay.domain.entity.RelayBrand;
import com.expnai.relay.domain.entity.RelayPocket;
import com.expnai.relay.domain.entity.RelayProduct;
import com.expnai.relay.features.product.dto.response.RelayListingDraftResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;

import lombok.RequiredArgsConstructor;

/**
 * Generates a Whop listing draft (title, short headline, price) using only data already
 * persisted on the product (brand, pocket, marketResearchJson, typePriceSuggestionsJson).
 * Never triggers new Apify, Etsy, or SerpAPI calls; falls back deterministically when no LLM key is available.
 */
@Service
@RequiredArgsConstructor
public class ListingLlmService {

	private static final Logger log = LoggerFactory.getLogger(ListingLlmService.class);
	private static final String GEMINI_MODEL = "gemini-3.8-flash";
	private static final String OPENAI_MODEL = "gpt-4o-mini";
	private static final int MAX_LEN = 80;
	private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)");

	private final ObjectMapper objectMapper;
	private final AiUsageMeter aiUsageMeter;

	private record PriceBand(BigDecimal min, BigDecimal max, BigDecimal midpoint, String rawPrice, String pages) {
	}

	public RelayListingDraftResponse generateDraft(
			String apiKey,
			RelayProduct product,
			RelayBrand brand,
			RelayPocket pocket) {
		PriceBand band = extractPriceBand(product);
		RelayListingDraftResponse fallback = buildDeterministicDraft(product, brand, pocket, band);

		if (apiKey == null || apiKey.isBlank()) {
			return fallback;
		}

		try {
			Long productId = product != null ? product.getId() : null;
			String prompt = buildPrompt(product, brand, pocket, band);
			String rawJson = apiKey.trim().startsWith("sk-")
					? callOpenAiCompatible(apiKey.trim(), prompt, productId)
					: callGemini(apiKey.trim(), prompt, productId);
			return parseResponse(rawJson, fallback, band);
		}
		catch (Exception ex) {
			log.warn("Listing draft LLM failed for product {} (using deterministic fallback): {}",
					product != null ? product.getId() : null, ex.getMessage());
			return fallback;
		}
	}

	private String buildPrompt(
			RelayProduct product,
			RelayBrand brand,
			RelayPocket pocket,
			PriceBand band) {
		StringBuilder sb = new StringBuilder();
		sb.append("""
				You are Relay, writing high-converting Whop storefront listing copy for a digital product.
				Return ONLY valid JSON with this exact schema:
				{
				  "title": "Crisp storefront product title (<= 80 characters, clear buyer benefit)",
				  "headline": "One punchy short description / subtitle (<= 80 characters, no fluff)",
				  "price": 19
				}

				RULES:
				1. Both `title` and `headline` MUST be <= 80 characters and plain text (no markdown, no quotes around them).
				2. `price` MUST be a positive whole USD number grounded in the suggested price band below when provided.
				3. Ground every claim in the product, pocket, and market context below. Do not invent fake metrics.
				""");

		if (brand != null && brand.getTitle() != null && !brand.getTitle().isBlank()) {
			sb.append("\nCover title: ").append(brand.getTitle().trim()).append("\n");
		}
		if (product != null) {
			if (product.getName() != null && !product.getName().isBlank()) {
				sb.append("Product name: ").append(product.getName().trim()).append("\n");
			}
			if (product.getProductType() != null) {
				sb.append("Format: ").append(product.getProductType().getCode().toLowerCase(Locale.ROOT)).append("\n");
			}
		}
		if (pocket != null) {
			if (pocket.getHeadline() != null && !pocket.getHeadline().isBlank()) {
				sb.append("Pocket headline: ").append(pocket.getHeadline().trim()).append("\n");
			}
			if (pocket.getNiche() != null && !pocket.getNiche().isBlank()) {
				sb.append("Niche: ").append(pocket.getNiche().trim()).append("\n");
			}
			if (pocket.getProblem() != null && !pocket.getProblem().isBlank()) {
				sb.append("Buyer problem: ").append(pocket.getProblem().trim()).append("\n");
			}
			if (pocket.getEvidenceJson() != null && !pocket.getEvidenceJson().isBlank()) {
				sb.append("Pocket evidence: ").append(truncate(pocket.getEvidenceJson(), 500)).append("\n");
			}
		}
		if (band != null) {
			sb.append("Suggested price band for this format: ").append(band.rawPrice());
			if (band.pages() != null && !band.pages().isBlank()) {
				sb.append(" (").append(band.pages()).append(")");
			}
			sb.append("\n");
		}
		if (product != null && product.getMarketResearchJson() != null && !product.getMarketResearchJson().isBlank()) {
			sb.append("Market research snapshot: ").append(truncate(product.getMarketResearchJson(), 900)).append("\n");
		}
		return sb.toString();
	}

	private RelayListingDraftResponse parseResponse(
			String rawJson,
			RelayListingDraftResponse fallback,
			PriceBand band) throws Exception {
		JsonNode root = objectMapper.readTree(rawJson);
		String title = clampText(root.path("title").asText(""), MAX_LEN);
		if (title.isEmpty()) {
			title = fallback.title();
		}
		String headline = clampText(root.path("headline").asText(""), MAX_LEN);
		if (headline.isEmpty()) {
			headline = fallback.headline();
		}

		BigDecimal price = null;
		JsonNode priceNode = root.path("price");
		if (priceNode.isNumber()) {
			price = priceNode.decimalValue();
		}
		else if (priceNode.isTextual()) {
			Matcher m = NUMBER_PATTERN.matcher(priceNode.asText(""));
			if (m.find()) {
				price = new BigDecimal(m.group(1));
			}
		}

		if (price == null || price.compareTo(BigDecimal.ONE) < 0 || price.compareTo(BigDecimal.valueOf(999)) > 0) {
			price = fallback.price();
		}
		else {
			price = price.setScale(0, RoundingMode.HALF_UP);
			if (band != null && band.min() != null && band.max() != null) {
				if (price.compareTo(band.min()) < 0) {
					price = band.min();
				}
				else if (price.compareTo(band.max()) > 0) {
					price = band.max();
				}
			}
		}

		return new RelayListingDraftResponse(title, headline, price);
	}

	private RelayListingDraftResponse buildDeterministicDraft(
			RelayProduct product,
			RelayBrand brand,
			RelayPocket pocket,
			PriceBand band) {
		String rawTitle = (brand != null && brand.getTitle() != null && !brand.getTitle().isBlank())
				? brand.getTitle().trim()
				: (product != null && product.getName() != null && !product.getName().isBlank())
						? product.getName().trim()
						: (pocket != null && pocket.getNiche() != null && !pocket.getNiche().isBlank())
								? pocket.getNiche().trim()
								: "Digital Product";

		String rawHeadline = (pocket != null && pocket.getHeadline() != null && !pocket.getHeadline().isBlank())
				? pocket.getHeadline().trim()
				: (pocket != null && pocket.getProblem() != null && !pocket.getProblem().isBlank())
						? pocket.getProblem().trim()
						: "Instant-download digital guide and templates";

		BigDecimal price = (band != null && band.midpoint() != null && band.midpoint().compareTo(BigDecimal.ONE) >= 0)
				? band.midpoint()
				: BigDecimal.valueOf(14);

		return new RelayListingDraftResponse(
				clampText(rawTitle, MAX_LEN),
				clampText(rawHeadline, MAX_LEN),
				price);
	}

	private PriceBand extractPriceBand(RelayProduct product) {
		if (product == null
				|| product.getProductType() == null
				|| product.getTypePriceSuggestionsJson() == null
				|| product.getTypePriceSuggestionsJson().isBlank()) {
			return null;
		}
		try {
			JsonNode root = objectMapper.readTree(product.getTypePriceSuggestionsJson());
			String typeKey = product.getProductType().getCode().toLowerCase(Locale.ROOT);
			JsonNode entry = root.path(typeKey);
			if (!entry.isObject()) {
				return null;
			}
			String rawPrice = entry.path("price").asText("").trim();
			String pages = entry.path("pages").asText("").trim();
			BigDecimal min = null;
			BigDecimal max = null;
			Matcher m = NUMBER_PATTERN.matcher(rawPrice);
			if (m.find()) {
				min = new BigDecimal(m.group(1));
				if (m.find()) {
					max = new BigDecimal(m.group(1));
				}
				else {
					max = min;
				}
			}
			BigDecimal midpoint = null;
			if (min != null && max != null) {
				midpoint = min.add(max).divide(BigDecimal.valueOf(2), 0, RoundingMode.HALF_UP);
			}
			else if (entry.path("median").isNumber()) {
				midpoint = entry.path("median").decimalValue().setScale(0, RoundingMode.HALF_UP);
			}
			return new PriceBand(min, max, midpoint, rawPrice, pages);
		}
		catch (Exception ignored) {
			return null;
		}
	}

	private String callGemini(String apiKey, String prompt, Long productId) {
		Client client = Client.builder().apiKey(apiKey).build();
		GenerateContentConfig config = GenerateContentConfig.builder()
				.responseMimeType("application/json")
				.candidateCount(1)
				.build();
		GenerateContentResponse response = client.models.generateContent(GEMINI_MODEL, prompt, config);
		String text = response.text();
		aiUsageMeter.recordGeminiChat(productId, 6, GEMINI_MODEL, response, prompt, text, "listing_draft");
		if (text == null || text.isBlank()) {
			throw new IllegalStateException("Empty listing draft response from Gemini");
		}
		return stripMarkdownFences(text);
	}

	private String callOpenAiCompatible(String apiKey, String prompt, Long productId) throws Exception {
		HttpClient http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(15))
				.build();
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("model", OPENAI_MODEL);
		payload.put("response_format", Map.of("type", "json_object"));
		payload.put("messages", List.of(
				Map.of("role", "system", "content", "You are Relay. Respond only with valid JSON."),
				Map.of("role", "user", "content", prompt)));

		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create("https://api.openai.com/v1/chat/completions"))
				.timeout(Duration.ofSeconds(30))
				.header("Authorization", "Bearer " + apiKey)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(
						objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8))
				.build();

		HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (resp.statusCode() >= 400) {
			throw new IllegalStateException("OpenAI listing draft HTTP " + resp.statusCode());
		}
		JsonNode root = objectMapper.readTree(resp.body());
		String content = root.path("choices").path(0).path("message").path("content").asText("");
		aiUsageMeter.recordOpenAiChat(productId, 6, OPENAI_MODEL, root, prompt, content, "listing_draft");
		if (content.isBlank()) {
			throw new IllegalStateException("Empty listing draft response from OpenAI");
		}
		return stripMarkdownFences(content);
	}

	private String clampText(String raw, int maxLen) {
		if (raw == null) {
			return "";
		}
		String clean = raw.replaceAll("\\s+", " ").trim();
		if (clean.length() <= maxLen) {
			return clean;
		}
		int cut = clean.lastIndexOf(' ', maxLen - 1);
		if (cut > maxLen / 2) {
			return clean.substring(0, cut).trim();
		}
		return clean.substring(0, maxLen).trim();
	}

	private String truncate(String raw, int maxLen) {
		if (raw == null) {
			return "";
		}
		String clean = raw.replaceAll("\\s+", " ").trim();
		return clean.length() <= maxLen ? clean : clean.substring(0, maxLen) + "…";
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

