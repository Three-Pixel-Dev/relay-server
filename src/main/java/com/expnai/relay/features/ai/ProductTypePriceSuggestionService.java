package com.expnai.relay.features.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

import com.expnai.relay.domain.entity.RelayPocket;
import com.expnai.relay.domain.entity.RelayProduct;
import com.expnai.relay.features.market.MarketResearchPack.EtsyKeywordResult;
import com.expnai.relay.features.market.MarketResearchService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;

import lombok.RequiredArgsConstructor;

/**
 * Computes AI-suggested buyer sell price ranges per product format grounded in live
 * Etsy / Apify listing prices. Reuses the pocket step's persisted {@code marketResearchJson}
 * (0 extra Apify calls) and falls back to at most ONE niche query when no pack is persisted.
 */
@Service
@RequiredArgsConstructor
public class ProductTypePriceSuggestionService {

	private static final Logger log = LoggerFactory.getLogger(ProductTypePriceSuggestionService.class);
	private static final String GEMINI_MODEL = "gemini-3.8-flash";
	private static final String OPENAI_MODEL = "gpt-4o-mini";

	private static final List<FormatSpec> FORMATS = List.of(
			new FormatSpec("ebook", "eBook / guide", 1.15),
			new FormatSpec("workbook", "Workbook", 1.25),
			new FormatSpec("planner", "Planner / tracker", 0.90),
			new FormatSpec("checklist", "Checklist / cheat sheet", 0.55),
			new FormatSpec("templates", "Template pack", 1.10),
			new FormatSpec("minicourse", "Mini-course PDF", 1.75));

	private final MarketResearchService marketResearchService;
	private final AiUsageMeter aiUsageMeter;
	private final ObjectMapper objectMapper;

	private record FormatSpec(String id, String label, double relativeMultiplier) {
	}

	public record TypePriceSuggestion(
			String price,
			BigDecimal median,
			String rationale,
			String source
	) {
	}

	public Map<String, TypePriceSuggestion> suggestPrices(
			String chatApiKey,
			RelayProduct product,
			RelayPocket pocket) {
		String topic = deriveTopic(product, pocket);

		// 1. Reuse persisted pocket-step marketplace results (0 extra Apify/Etsy calls)
		List<EtsyKeywordResult> marketRows = extractPricedResultsFromPack(
				product != null ? product.getMarketResearchJson() : null);

		// 2. Fallback: at most ONE niche query if persisted pack has no priced marketplace rows
		if (marketRows.isEmpty() && !topic.isBlank()) {
			String singleQuery = topic + " digital download";
			Map<String, EtsyKeywordResult> fetched = marketResearchService.searchMarketplaceKeywords(
					List.of(singleQuery));
			marketRows = fetched.values().stream()
					.filter(r -> r != null && r.medianPrice() != null && r.medianPrice().signum() > 0)
					.toList();
		}

		if (marketRows.isEmpty()) {
			return Map.of();
		}

		BigDecimal anchorMedian = computeAggregateMedian(marketRows);
		String primarySource = marketRows.get(0).sourceLabel();

		// 3. One LLM call maps shared niche marketplace evidence onto all six product formats
		if (chatApiKey != null && !chatApiKey.isBlank()) {
			try {
				Long productId = product != null ? product.getId() : null;
				String prompt = buildPrompt(product, pocket, topic, marketRows);
				String rawJson = chatApiKey.trim().startsWith("sk-")
						? callOpenAi(chatApiKey.trim(), prompt, productId)
						: callGemini(chatApiKey.trim(), prompt, productId);
				Map<String, TypePriceSuggestion> parsed = parseLlmSuggestions(rawJson, anchorMedian, primarySource);
				if (!parsed.isEmpty()) {
					return parsed;
				}
			}
			catch (Exception ex) {
				log.warn("LLM type price suggestion failed for product {} (using deterministic market bands): {}",
						product != null ? product.getId() : null, ex.getMessage());
			}
		}

		return buildDeterministicSuggestions(anchorMedian, primarySource);
	}

	private List<EtsyKeywordResult> extractPricedResultsFromPack(String marketResearchJson) {
		if (marketResearchJson == null || marketResearchJson.isBlank()) {
			return List.of();
		}
		try {
			JsonNode root = objectMapper.readTree(marketResearchJson);
			JsonNode etsyNode = root.path("etsyResults");
			if (!etsyNode.isArray() || etsyNode.isEmpty()) {
				return List.of();
			}
			List<EtsyKeywordResult> list = objectMapper.convertValue(
					etsyNode, new TypeReference<List<EtsyKeywordResult>>() {
					});
			if (list == null) {
				return List.of();
			}
			return list.stream()
					.filter(r -> r != null && r.medianPrice() != null && r.medianPrice().signum() > 0)
					.toList();
		}
		catch (Exception ex) {
			log.debug("Could not parse persisted marketResearchJson: {}", ex.getMessage());
			return List.of();
		}
	}

	private BigDecimal computeAggregateMedian(List<EtsyKeywordResult> rows) {
		List<BigDecimal> medians = new ArrayList<>(rows.size());
		for (EtsyKeywordResult r : rows) {
			if (r.medianPrice() != null && r.medianPrice().signum() > 0) {
				medians.add(r.medianPrice());
			}
		}
		if (medians.isEmpty()) {
			return BigDecimal.TEN;
		}
		medians.sort(BigDecimal::compareTo);
		int mid = medians.size() / 2;
		BigDecimal val = (medians.size() % 2 == 1)
				? medians.get(mid)
				: medians.get(mid - 1).add(medians.get(mid)).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
		return val.setScale(2, RoundingMode.HALF_UP);
	}

	private String deriveTopic(RelayProduct product, RelayPocket pocket) {
		String raw = null;
		if (pocket != null && pocket.getNiche() != null && !pocket.getNiche().isBlank()) {
			raw = pocket.getNiche().trim();
		}
		else if (product != null && product.getInput() != null && !product.getInput().isBlank()) {
			raw = product.getInput().trim();
		}
		else if (product != null && product.getName() != null && !product.getName().isBlank()) {
			raw = product.getName().trim();
		}
		if (raw == null) {
			return "";
		}
		String[] words = raw.replaceAll("[^a-zA-Z0-9\\s-]", " ").trim().split("\\s+");
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < Math.min(words.length, 4); i++) {
			if (!words[i].isBlank()) {
				if (!sb.isEmpty()) {
					sb.append(' ');
				}
				sb.append(words[i]);
			}
		}
		return sb.toString().trim();
	}

	private String buildPrompt(
			RelayProduct product,
			RelayPocket pocket,
			String topic,
			List<EtsyKeywordResult> marketRows) {
		StringBuilder sb = new StringBuilder();
		sb.append("""
				You are a digital product pricing strategist.
				Given the chosen pocket and REAL live marketplace listing prices for this niche below, suggest a realistic buyer sell price range (whole USD integers min and max) for ALL SIX digital product formats:
				- ebook (eBook / guide)
				- workbook (Workbook)
				- planner (Planner / tracker)
				- checklist (Checklist / cheat sheet)
				- templates (Template pack)
				- minicourse (Mini-course PDF)

				RULES:
				1. Return ONLY valid JSON mapping all six format ids to an object:
				{
				  "ebook": { "min": 9, "max": 22, "rationale": "One short phrase grounded in the observed niche median." },
				  "workbook": { "min": 12, "max": 25, "rationale": "..." },
				  "planner": { "min": 5, "max": 14, "rationale": "..." },
				  "checklist": { "min": 3, "max": 9, "rationale": "..." },
				  "templates": { "min": 9, "max": 21, "rationale": "..." },
				  "minicourse": { "min": 17, "max": 39, "rationale": "..." }
				}
				2. Anchor every format's [min, max] to the observed niche marketplace prices below (cheaper for checklists/planners, mid-band for guides/workbooks/templates, higher for mini-courses).
				3. min and max must be positive whole integers with max >= min.
				""");

		if (!topic.isBlank()) {
			sb.append("\nTopic: ").append(topic).append("\n");
		}
		if (pocket != null) {
			if (pocket.getHeadline() != null && !pocket.getHeadline().isBlank()) {
				sb.append("Pocket headline: ").append(pocket.getHeadline().trim()).append("\n");
			}
			if (pocket.getProblem() != null && !pocket.getProblem().isBlank()) {
				sb.append("Buyer problem: ").append(pocket.getProblem().trim()).append("\n");
			}
		}
		if (product != null && product.getStepNote() != null && !product.getStepNote().isBlank()) {
			sb.append("User note: ").append(product.getStepNote().trim()).append("\n");
		}

		sb.append("\n=== OBSERVED NICHE MARKETPLACE LISTINGS ===\n");
		for (EtsyKeywordResult r : marketRows) {
			sb.append("- ").append(r.sourceLabel())
					.append(" keyword \"").append(r.keyword()).append("\": ")
					.append(r.activeListingsCount()).append(" listings, median $")
					.append(r.medianPrice().setScale(2, RoundingMode.HALF_UP));
			if (r.minPrice() != null && r.maxPrice() != null) {
				sb.append(" (range $").append(r.minPrice().setScale(2, RoundingMode.HALF_UP))
						.append("–$").append(r.maxPrice().setScale(2, RoundingMode.HALF_UP)).append(")");
			}
			sb.append("\n");
		}
		return sb.toString();
	}

	private Map<String, TypePriceSuggestion> parseLlmSuggestions(
			String rawJson,
			BigDecimal anchorMedian,
			String sourceLabel) throws Exception {
		JsonNode root = objectMapper.readTree(rawJson);
		Map<String, TypePriceSuggestion> out = new LinkedHashMap<>();

		for (FormatSpec fmt : FORMATS) {
			JsonNode node = root.path(fmt.id());
			if (!node.isObject()) {
				continue;
			}
			int min = node.path("min").asInt(0);
			int max = node.path("max").asInt(0);
			if (min <= 0 || max <= 0) {
				continue;
			}
			if (max < min) {
				int tmp = min;
				min = max;
				max = tmp;
			}
			min = Math.max(1, Math.min(299, min));
			max = Math.max(min, Math.min(499, max));
			String formatted = min == max ? ("$" + min) : ("$" + min + "–" + max);
			String rationale = node.path("rationale").asText("").trim();
			out.put(fmt.id(), new TypePriceSuggestion(
					formatted,
					anchorMedian,
					rationale.isEmpty() ? null : rationale,
					sourceLabel));
		}
		return out;
	}

	private Map<String, TypePriceSuggestion> buildDeterministicSuggestions(
			BigDecimal anchorMedian,
			String sourceLabel) {
		Map<String, TypePriceSuggestion> out = new LinkedHashMap<>();
		double base = Math.max(3.0, anchorMedian.doubleValue());
		for (FormatSpec fmt : FORMATS) {
			double scaled = base * fmt.relativeMultiplier();
			int min = Math.max(2, (int) Math.floor(scaled * 0.72));
			int max = Math.max(min + 3, (int) Math.ceil(scaled * 1.45));
			out.put(fmt.id(), new TypePriceSuggestion(
					"$" + min + "–" + max,
					anchorMedian,
					"Anchored to live niche marketplace median ($" + anchorMedian + ")",
					sourceLabel));
		}
		return out;
	}

	private String callGemini(String apiKey, String prompt, Long productId) {
		Client client = Client.builder().apiKey(apiKey).build();
		GenerateContentConfig config = GenerateContentConfig.builder()
				.responseMimeType("application/json")
				.candidateCount(1)
				.build();
		GenerateContentResponse response = client.models.generateContent(GEMINI_MODEL, prompt, config);
		String text = response.text();
		aiUsageMeter.recordGeminiChat(productId, 2, GEMINI_MODEL, response, prompt, text, "type_prices");
		if (text == null || text.isBlank()) {
			throw new IllegalStateException("Empty type prices response from Gemini");
		}
		return stripMarkdownFences(text);
	}

	private String callOpenAi(String apiKey, String prompt, Long productId) throws Exception {
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
			throw new IllegalStateException("OpenAI type prices HTTP " + resp.statusCode());
		}
		JsonNode root = objectMapper.readTree(resp.body());
		String content = root.path("choices").path(0).path("message").path("content").asText("");
		aiUsageMeter.recordOpenAiChat(productId, 2, OPENAI_MODEL, root, prompt, content, "type_prices");
		if (content.isBlank()) {
			throw new IllegalStateException("Empty type prices response from OpenAI");
		}
		return stripMarkdownFences(content);
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
