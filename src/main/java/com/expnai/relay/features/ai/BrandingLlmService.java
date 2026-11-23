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
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

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

	private static final Pattern HEX6_PATTERN = Pattern.compile("^#[0-9a-fA-F]{6}$");
	private static final Pattern HEX3_PATTERN = Pattern.compile("^#[0-9a-fA-F]{3}$");

	public static final List<BrandPalette> HOUSE_PALETTES = List.of(
			new BrandPalette(
					"Editorial",
					List.of("#f4f1ea", "#25262b", "#8a3b2e", "#dccdbf", "#5f6168"),
					"Merriweather",
					"Open Sans"),
			new BrandPalette(
					"Calm",
					List.of("#f7f6f2", "#1f2a44", "#3b7a6e", "#cfe0d8", "#6b7280"),
					"Fraunces",
					"Inter"),
			new BrandPalette(
					"Bold",
					List.of("#fffdf5", "#14121c", "#ffcf5a", "#ffe9b0", "#4b4563"),
					"Sora",
					"Sora"));

	private static final List<String> STOCK_COVER_DIRECTIONS = List.of(
			"Classic high-craft editorial cover: light background, bold asymmetric typography at the top, rich accent band and minimalist subject illustration below.",
			"Dark dramatic editorial cover: deep primary ink background, luminous accent focal artwork in the lower half, crisp light typography at the top.",
			"Refined centered modern book cover: clean light background, centered display typography paired with a minimalist geometric or organic motif inside a framed card.");

	private static final List<String> ALLOWED_FONTS = List.of(
			"Merriweather",
			"Fraunces",
			"Sora",
			"Playfair Display",
			"Libre Baskerville",
			"Source Serif 4",
			"Open Sans",
			"Inter",
			"DM Sans",
			"Space Grotesk",
			"IBM Plex Sans");

	private final ObjectMapper objectMapper;
	private final AiUsageMeter aiUsageMeter;

	public record BrandPalette(
			String name,
			List<String> cols,
			String display,
			String body
	) {
		public String typePair() {
			return display + " / " + body;
		}
	}

	public record GeneratedBrand(
			String title,
			BrandPalette palette,
			List<String> coverDirections,
			int preferredCover,
			String rationale
	) {
		public String typePair() {
			return palette != null ? palette.typePair() : "Merriweather / Open Sans";
		}
	}

	public static BrandPalette housePalette(Integer paletteIndex) {
		int idx = paletteIndex != null
				? Math.max(0, Math.min(HOUSE_PALETTES.size() - 1, paletteIndex))
				: 0;
		return HOUSE_PALETTES.get(idx);
	}

	public static String typePairForPalette(int paletteIndex) {
		return housePalette(paletteIndex).typePair();
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
				You are Relay, a digital product brand & cover art director.
				Invent a cohesive, subject-specific brand system for this digital product:
				- A crisp, marketable cover title (3–7 words).
				- A custom 5-color palette tailored to the niche and buyer mood (NOT generic defaults):
				  cols[0]: light page/paper background hex (#rrggbb)
				  cols[1]: dark primary ink/text hex (#rrggbb, strong contrast against cols[0])
				  cols[2]: signature brand accent hex (#rrggbb)
				  cols[3]: soft tint / secondary surface hex (#rrggbb)
				  cols[4]: muted secondary ink hex (#rrggbb)
				- A font pair chosen ONLY from this allowlist:
				  Merriweather, Fraunces, Sora, Playfair Display, Libre Baskerville, Source Serif 4, Open Sans, Inter, DM Sans, Space Grotesk, IBM Plex Sans
				- Three distinct, specific cover art directions (composition, mood, visual motif — each genuinely different from the others so the user has three strong cover options to pick from).
				- preferredCover: index 0, 1, or 2 of the direction you recommend most.

				Return ONLY valid JSON matching this exact schema:
				{
				  "title": "Crisp cover title (3-7 words, punchy and readable on a book cover)",
				  "palette": {
				    "name": "Evocative 1-3 word palette name (e.g. Botanical Ink, Warm Studio, Cobalt Ledger)",
				    "cols": ["#f6f3ec", "#1d2420", "#b84a39", "#e3d6c6", "#5a635e"],
				    "display": "Playfair Display",
				    "body": "Inter"
				  },
				  "covers": [
				    "Direction 0: 1-2 sentences describing layout, background tone, and subject-specific motif.",
				    "Direction 1: 1-2 sentences describing a distinct mood/composition (e.g. dark ink ground or bold typographic poster).",
				    "Direction 2: 1-2 sentences describing a third distinct layout/motif."
				  ],
				  "preferredCover": 0,
				  "rationale": "One short sentence explaining why this title, palette, and typography fit the buyer."
				}
				""");

		if (product != null) {
			if (product.getName() != null && !product.getName().isBlank()) {
				sb.append("\nWorking product name: ").append(product.getName().trim()).append("\n");
			}
			if (product.getProductType() != null) {
				sb.append("Product type: ").append(product.getProductType().getCode().toLowerCase(Locale.ROOT)).append("\n");
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
		String fallbackTitle = (product != null && product.getName() != null && !product.getName().isBlank())
				? product.getName().trim()
				: (pocket != null && pocket.getNiche() != null && !pocket.getNiche().isBlank())
						? pocket.getNiche().trim()
						: "Practical Field Guide";
		try {
			JsonNode node = objectMapper.readTree(rawJson);
			String title = node.path("title").asText("").trim();
			if (title.isEmpty()) {
				title = fallbackTitle;
			}

			BrandPalette palette = parsePalette(node.path("palette"));
			List<String> coverDirections = parseCoverDirections(node.path("covers"));
			int preferredCover = node.has("preferredCover")
					? node.path("preferredCover").asInt(0)
					: node.path("cover").asInt(0);
			preferredCover = Math.max(0, Math.min(2, preferredCover));

			String rationale = node.path("rationale").asText("").trim();
			return new GeneratedBrand(title, palette, coverDirections, preferredCover, rationale);
		}
		catch (Exception ex) {
			log.warn("Failed to parse custom branding JSON (falling back to Editorial): {}", ex.getMessage());
			return new GeneratedBrand(
					fallbackTitle,
					HOUSE_PALETTES.get(0),
					STOCK_COVER_DIRECTIONS,
					0,
					"Editorial palette and balanced cover directions selected for readability.");
		}
	}

	private BrandPalette parsePalette(JsonNode paletteNode) {
		BrandPalette fallback = HOUSE_PALETTES.get(0);
		if (paletteNode == null || !paletteNode.isObject()) {
			return fallback;
		}

		String name = paletteNode.path("name").asText("").trim();
		if (name.isEmpty()) {
			name = "Custom Studio";
		}
		if (name.length() > 48) {
			name = name.substring(0, 48).trim();
		}

		List<String> cols = new ArrayList<>(5);
		JsonNode colsNode = paletteNode.path("cols");
		for (int i = 0; i < 5; i++) {
			String raw = (colsNode.isArray() && i < colsNode.size()) ? colsNode.get(i).asText("") : "";
			String normalized = normalizeHex(raw);
			cols.add(normalized != null ? normalized : fallback.cols().get(i));
		}

		String display = clampFont(paletteNode.path("display").asText(""), "Merriweather");
		String body = clampFont(paletteNode.path("body").asText(""), "Open Sans");

		return new BrandPalette(name, List.copyOf(cols), display, body);
	}

	private List<String> parseCoverDirections(JsonNode coversNode) {
		List<String> directions = new ArrayList<>(3);
		if (coversNode != null && coversNode.isArray()) {
			for (JsonNode item : coversNode) {
				String dir = item.asText("").replaceAll("\\s+", " ").trim();
				if (!dir.isEmpty() && directions.size() < 3) {
					directions.add(dir.length() > 360 ? dir.substring(0, 360).trim() : dir);
				}
			}
		}
		while (directions.size() < 3) {
			directions.add(STOCK_COVER_DIRECTIONS.get(directions.size()));
		}
		return List.copyOf(directions);
	}

	public static String normalizeHex(String raw) {
		if (raw == null) {
			return null;
		}
		String trimmed = raw.trim();
		if (!trimmed.startsWith("#") && (trimmed.length() == 6 || trimmed.length() == 3)) {
			trimmed = "#" + trimmed;
		}
		if (HEX6_PATTERN.matcher(trimmed).matches()) {
			return trimmed.toLowerCase(Locale.ROOT);
		}
		if (HEX3_PATTERN.matcher(trimmed).matches()) {
			char r = Character.toLowerCase(trimmed.charAt(1));
			char g = Character.toLowerCase(trimmed.charAt(2));
			char b = Character.toLowerCase(trimmed.charAt(3));
			return "#" + r + r + g + g + b + b;
		}
		return null;
	}

	public static String clampFont(String rawFont, String defaultFont) {
		if (rawFont == null || rawFont.isBlank()) {
			return defaultFont;
		}
		String cleaned = rawFont.trim();
		for (String allowed : ALLOWED_FONTS) {
			if (allowed.equalsIgnoreCase(cleaned)) {
				return allowed;
			}
		}
		String lower = cleaned.toLowerCase(Locale.ROOT);
		for (String allowed : ALLOWED_FONTS) {
			if (lower.contains(allowed.toLowerCase(Locale.ROOT))) {
				return allowed;
			}
		}
		return defaultFont;
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
