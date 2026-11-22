package com.expnai.relay.features.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.expnai.relay.common.exception.BusinessRuleException;
import com.expnai.relay.domain.entity.RelayProduct;
import com.expnai.relay.domain.entity.RelaySourceProfile;
import com.expnai.relay.features.market.MarketResearchPack;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PocketLlmService {

	private static final Logger log = LoggerFactory.getLogger(PocketLlmService.class);
	private static final String GEMINI_MODEL = "gemini-3.8-flash";
	private static final String OPENAI_MODEL = "gpt-4o-mini";

	private final ObjectMapper objectMapper;
	private final AiUsageMeter aiUsageMeter;

	public record GeneratedPocketScores(int pain, int worse, int power, int speed) {
	}

	public record GeneratedPocket(
			String h,
			String ind,
			String niche,
			String know,
			String prob,
			GeneratedPocketScores scores,
			List<String> src,
			boolean rec,
			boolean grounded
	) {
	}

	public List<String> deriveKeywords(String apiKey, RelayProduct product, RelaySourceProfile profile) {
		try {
			if (apiKey != null && !apiKey.isBlank()) {
				Long productId = product != null ? product.getId() : null;
				String prompt = buildKeywordPrompt(product, profile);
				String rawJson = callLlmJson(apiKey.trim(), prompt, productId, "keywords");
				JsonNode root = objectMapper.readTree(rawJson);
				JsonNode arr = root.path("keywords");
				Set<String> keywords = new LinkedHashSet<>();
				if (arr.isArray()) {
					for (JsonNode n : arr) {
						String kw = n.asText("").trim();
						if (!kw.isEmpty() && kw.length() <= 60) {
							keywords.add(kw);
						}
						if (keywords.size() >= 5) {
							break;
						}
					}
				}
				if (keywords.size() >= 3) {
					return List.copyOf(keywords);
				}
			}
		}
		catch (Exception ex) {
			log.warn("Keyword derivation LLM call failed for product {} (using deterministic fallback): {}",
					product != null ? product.getId() : null, ex.getMessage());
		}

		return deriveFallbackKeywords(product, profile);
	}

	public List<GeneratedPocket> generatePockets(
			String apiKey,
			RelayProduct product,
			RelaySourceProfile profile,
			MarketResearchPack researchPack) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new BusinessRuleException("Missing Text model API key for pocket generation.");
		}
		Long productId = product != null ? product.getId() : null;
		String prompt = buildPocketsPrompt(product, profile, researchPack);
		String rawJson = callLlmJson(apiKey.trim(), prompt, productId, "pockets");
		return parsePocketsResponse(rawJson, profile != null);
	}

	private String buildKeywordPrompt(RelayProduct product, RelaySourceProfile profile) {
		StringBuilder sb = new StringBuilder();
		sb.append("""
				You are a digital product market researcher.
				Given the product context below, return 4 concise buyer-intent search keywords (2-5 words each) suitable for querying Etsy digital products, Google Search, and Google Trends.
				Return ONLY valid JSON:
				{
				  "keywords": ["keyword 1", "keyword 2", "keyword 3", "keyword 4"]
				}
				""");
		appendProductContext(sb, product, profile);
		return sb.toString();
	}

	private String buildPocketsPrompt(
			RelayProduct product,
			RelaySourceProfile profile,
			MarketResearchPack researchPack) {
		boolean hasProfile = profile != null;
		StringBuilder sb = new StringBuilder();
		sb.append("""
				You are Relay, a digital product strategist.
				Your job is to propose EXACTLY 4 profitable digital product pockets based on the user's context, their source summary (if provided), and the REAL market research pack below.

				STRICT RULES:
				1. Return ONLY valid JSON matching this exact schema:
				{
				  "pockets": [
				    {
				      "h": "I help [specific buyer] [achieve concrete outcome] without [specific pain/objection]",
				      "ind": "Industry (2-3 words)",
				      "niche": "Concrete niche label (3-6 words)",
				      "know": "1-2 sentences describing the knowledge/method powering this product",
				      "prob": "1-2 sentences describing the urgent buyer problem",
				      "scores": { "pain": 86, "worse": 80, "power": 78, "speed": 88 },
				      "src": [
				        "Etsy Open API (YYYY-MM-DD): <real fact from pack — or 'Etsy via Apify (YYYY-MM-DD): ...' matching the pack>",
				        "Google / SerpAPI (YYYY-MM-DD): <real fact from pack>",
				        "Google Trends (YYYY-MM-DD): <real relative interest fact from pack>",
				        "Your sources: <theme or insight from SourceProfile when present>"
				      ],
				      "rec": true,
				      "grounded": true
				    }
				  ]
				}
				2. Return EXACTLY 4 distinct pockets. Set "rec": true on EXACTLY ONE pocket (the strongest overall opportunity) and false on the other three.
				3. Every "h" MUST follow the "I help X do Y without Z" format.
				4. Scores ("pain", "worse", "power", "speed") MUST be integers between 1 and 100:
				   - pain: urgency & emotional weight of the problem
				   - worse: cost of inaction / problem getting worse over time
				   - power: buyer willingness & ability to pay
				   - speed: how quickly the buyer sees a tangible win
				5. ZERO INVENTED NUMBERS: Every marketplace, search, or trends bullet in "src" MUST ONLY cite real numbers, prices, related queries, or relative interest scores from the Market Research Pack below, and MUST match the pack's exact source label and queried date (e.g. "Etsy Open API (2026-11-22): ...", "Etsy via Apify (2026-11-22): ...", "Google / SerpAPI (2026-11-22): ...", "Google Trends (2026-11-22): ..."). Never label Apify data as "Etsy Open API".
				6. NEVER pretend Google Trends relative interest (0-100) is "Etsy searches/mo" or monthly search volume. Label Google/SerpAPI as Google/SerpAPI, never as Etsy searches.
				7. If a provider is listed under UNAVAILABLE / SKIPPED PROVIDERS, do NOT fabricate bullets for it.
				""");

		if (hasProfile) {
			sb.append("""
					8. A SourceProfile is present below. Ground pockets in the creator's actual niche, themes, audience, and voice. Include at least one "Your sources: ..." line in "src" referencing a real theme from the profile and set "grounded": true on pockets backed by the sources.
					""");
		}
		else {
			sb.append("""
					8. No uploaded SourceProfile is present; set "grounded": false on all pockets and do not invent "Your sources:" lines.
					""");
		}

		appendProductContext(sb, product, profile);
		sb.append("\n").append(researchPack.toPromptText()).append("\n");
		return sb.toString();
	}

	private void appendProductContext(StringBuilder sb, RelayProduct product, RelaySourceProfile profile) {
		if (product != null) {
			if (product.getName() != null && !product.getName().isBlank()) {
				sb.append("\nProduct name: ").append(product.getName().trim()).append("\n");
			}
			if (product.getInput() != null && !product.getInput().isBlank()) {
				sb.append("User's input / subject: ").append(product.getInput().trim()).append("\n");
			}
			if (product.isForElse() && product.getClientName() != null && !product.getClientName().isBlank()) {
				sb.append("Agency mode client: ").append(product.getClientName().trim()).append("\n");
			}
			if (product.getStepNote() != null && !product.getStepNote().isBlank()) {
				sb.append("User's requested adjustment / suggestion (MUST incorporate this): ")
						.append(product.getStepNote().trim()).append("\n");
			}
		}
		if (profile != null) {
			sb.append("\n=== SOURCE PROFILE (Your Sources) ===\n");
			if (profile.getNiche() != null && !profile.getNiche().isBlank()) {
				sb.append("Niche: ").append(profile.getNiche().trim()).append("\n");
			}
			if (profile.getTransformation() != null && !profile.getTransformation().isBlank()) {
				sb.append("Transformation: ").append(profile.getTransformation().trim()).append("\n");
			}
			if (profile.getAudience() != null && !profile.getAudience().isBlank()) {
				sb.append("Audience: ").append(profile.getAudience().trim()).append("\n");
			}
			if (profile.getVoice() != null && !profile.getVoice().isBlank()) {
				sb.append("Voice: ").append(profile.getVoice().trim()).append("\n");
			}
			if (profile.getVerdict() != null && !profile.getVerdict().isBlank()) {
				sb.append("Verdict: ").append(profile.getVerdict().trim()).append("\n");
			}
			if (profile.getThemesJson() != null && !profile.getThemesJson().isBlank()) {
				sb.append("Themes JSON: ").append(profile.getThemesJson()).append("\n");
			}
		}
	}

	private List<String> deriveFallbackKeywords(RelayProduct product, RelaySourceProfile profile) {
		Set<String> keywords = new LinkedHashSet<>();
		if (profile != null && profile.getNiche() != null && !profile.getNiche().isBlank()) {
			keywords.add(profile.getNiche().trim());
			keywords.add(profile.getNiche().trim() + " workbook");
			keywords.add(profile.getNiche().trim() + " guide");
		}
		if (product != null && product.getInput() != null && !product.getInput().isBlank()) {
			String trimmed = product.getInput().trim();
			keywords.add(trimmed.length() > 50 ? trimmed.substring(0, 50).trim() : trimmed);
		}
		if (product != null && product.getName() != null && !product.getName().isBlank()) {
			keywords.add(product.getName().trim());
		}
		if (keywords.isEmpty()) {
			keywords.add("digital planner template");
			keywords.add("printable workbook pdf");
			keywords.add("step by step guide");
		}
		return keywords.stream().limit(4).toList();
	}

	private List<GeneratedPocket> parsePocketsResponse(String rawJson, boolean hasProfile) {
		try {
			JsonNode root = objectMapper.readTree(rawJson);
			JsonNode array = root.has("pockets") ? root.path("pockets") : root;
			if (!array.isArray() || array.isEmpty()) {
				throw new BusinessRuleException("Model did not return a pockets array.");
			}

			List<GeneratedPocket> pockets = new ArrayList<>();
			boolean anyRec = false;

			for (JsonNode node : array) {
				if (pockets.size() >= 4) {
					break;
				}
				String h = textOrDefault(node, "h", "I help learners get results faster without guesswork");
				String ind = textOrDefault(node, "ind", "Digital Education");
				String niche = textOrDefault(node, "niche", "Actionable digital guide");
				String know = textOrDefault(node, "know", "Structured framework built from practical experience.");
				String prob = textOrDefault(node, "prob", "Buyers struggle to turn scattered advice into a repeatable plan.");

				JsonNode scoresNode = node.path("scores");
				GeneratedPocketScores scores = new GeneratedPocketScores(
						clampScore(scoresNode.path("pain").asInt(75)),
						clampScore(scoresNode.path("worse").asInt(72)),
						clampScore(scoresNode.path("power").asInt(74)),
						clampScore(scoresNode.path("speed").asInt(78)));

				List<String> src = new ArrayList<>();
				JsonNode srcNode = node.path("src");
				if (srcNode.isArray()) {
					for (JsonNode item : srcNode) {
						String line = item.asText("").trim();
						if (!line.isEmpty()) {
							src.add(line);
						}
					}
				}

				boolean rec = node.path("rec").asBoolean(false);
				if (rec) {
					if (anyRec) {
						rec = false;
					}
					else {
						anyRec = true;
					}
				}
				boolean grounded = hasProfile && node.path("grounded").asBoolean(false);

				pockets.add(new GeneratedPocket(h, ind, niche, know, prob, scores, List.copyOf(src), rec, grounded));
			}

			if (pockets.isEmpty()) {
				throw new BusinessRuleException("No pockets could be parsed from model response.");
			}

			if (!anyRec) {
				GeneratedPocket first = pockets.get(0);
				pockets.set(0, new GeneratedPocket(
						first.h(), first.ind(), first.niche(), first.know(), first.prob(),
						first.scores(), first.src(), true, first.grounded()));
			}

			return pockets;
		}
		catch (BusinessRuleException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new BusinessRuleException("Failed to parse pockets JSON: " + ex.getMessage());
		}
	}

	private String callLlmJson(String apiKey, String prompt, Long productId, String operation) {
		if (apiKey.startsWith("sk-")) {
			return callOpenAiCompatible(apiKey, prompt, productId, operation);
		}
		return callGemini(apiKey, prompt, productId, operation);
	}

	private String callGemini(String apiKey, String prompt, Long productId, String operation) {
		try {
			Client client = Client.builder().apiKey(apiKey).build();
			GenerateContentConfig config = GenerateContentConfig.builder()
					.responseMimeType("application/json")
					.candidateCount(1)
					.build();
			GenerateContentResponse response = client.models.generateContent(GEMINI_MODEL, prompt, config);
			String text = response.text();
			aiUsageMeter.recordGeminiChat(productId, 1, GEMINI_MODEL, response, prompt, text, operation);
			if (text == null || text.isBlank()) {
				throw new BusinessRuleException("Gemini returned an empty pockets response.");
			}
			return stripMarkdownFences(text);
		}
		catch (BusinessRuleException ex) {
			throw ex;
		}
		catch (Exception ex) {
			log.error("Gemini pocket generation failed: {}", ex.getMessage());
			throw new BusinessRuleException("Gemini pocket generation failed: " + ex.getMessage());
		}
	}

	private String callOpenAiCompatible(String apiKey, String prompt, Long productId, String operation) {
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
			aiUsageMeter.recordOpenAiChat(productId, 1, OPENAI_MODEL, root, prompt, content, operation);
			if (content.isBlank()) {
				throw new BusinessRuleException("Text model returned an empty pockets response.");
			}
			return stripMarkdownFences(content);
		}
		catch (BusinessRuleException ex) {
			throw ex;
		}
		catch (Exception ex) {
			log.error("OpenAI pocket generation failed: {}", ex.getMessage());
			throw new BusinessRuleException("Text model pocket generation failed: " + ex.getMessage());
		}
	}

	private String textOrDefault(JsonNode node, String field, String fallback) {
		String val = node.path(field).asText("").trim();
		return val.isEmpty() ? fallback : val;
	}

	private int clampScore(int val) {
		return Math.max(1, Math.min(100, val));
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

