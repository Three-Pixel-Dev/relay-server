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
public class ManuscriptLlmService {

	private static final Logger log = LoggerFactory.getLogger(ManuscriptLlmService.class);
	private static final String GEMINI_MODEL = "gemini-3.8-flash";
	private static final String OPENAI_MODEL = "gpt-4o-mini";

	private final ObjectMapper objectMapper;
	private final AiUsageMeter aiUsageMeter;

	public String writeSection(
			String apiKey,
			RelayProduct product,
			RelayPocket pocket,
			RelaySourceProfile profile,
			RelaySection section,
			int sectionIndex,
			int totalSections,
			List<RelaySection> allSections,
			List<RetrievedPassage> passages) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new BusinessRuleException("Missing Text model API key for manuscript writing.");
		}
		Long productId = product != null ? product.getId() : null;
		String prompt = buildPrompt(
				product, pocket, profile, section, sectionIndex, totalSections, allSections, passages);
		String raw = apiKey.trim().startsWith("sk-")
				? callOpenAiCompatible(apiKey.trim(), prompt, productId)
				: callGemini(apiKey.trim(), prompt, productId);
		return stripOuterMarkdownFence(raw);
	}

	private String buildPrompt(
			RelayProduct product,
			RelayPocket pocket,
			RelaySourceProfile profile,
			RelaySection section,
			int sectionIndex,
			int totalSections,
			List<RelaySection> allSections,
			List<RetrievedPassage> passages) {
		RelayProductType type = product != null ? product.getProductType() : null;
		String lengthGuidance = lengthGuidanceForType(type);
		String typeLabel = type != null ? type.getCode().toLowerCase() : "workbook";

		StringBuilder sb = new StringBuilder();
		sb.append("""
				You are Relay, writing a high-craft, publishable digital product section for a real buyer.
				Write ONLY the Markdown body for the requested section (do NOT repeat the section number/title as an H1 at the very top; use clear subheadings `##` / `###`, short paragraphs, bullet/numbered lists, callout blockquotes `>`, and practical exercises/fill-in prompts where appropriate).

				STRICT RULES:
				1. Format & length: This is a %s. %s
				2. Ground every concrete technique, story, or claim in the retrieved source passages when provided. Do NOT invent marketplace statistics, fake case studies, or bogus percentages.
				3. Match the creator's voice and speak directly to the target audience facing this exact moment.
				4. You may use compact GFM pipe tables (`| Column | Column |`) for side-by-side comparisons, frameworks, or checklists when helpful (keep tables compact: 2–4 columns, short cells).
				5. Return clean Markdown prose only — NO Markdown image syntax (`![...](...)`), NO raw HTML tags, NO JSON wrapper, no preamble, and no meta-commentary about being an AI (visuals are generated separately as product branding assets).
				""".formatted(typeLabel, lengthGuidance));

		if (product != null) {
			if (product.getName() != null && !product.getName().isBlank()) {
				sb.append("\nProduct name: ").append(product.getName().trim()).append("\n");
			}
			if (product.getStepNote() != null && !product.getStepNote().isBlank()) {
				sb.append("User's voice / style adjustment note (MUST follow this): ")
						.append(product.getStepNote().trim()).append("\n");
			}
		}

		if (pocket != null) {
			sb.append("\n=== POCKET ===\n");
			if (pocket.getHeadline() != null) {
				sb.append("Positioning: ").append(pocket.getHeadline()).append("\n");
			}
			if (pocket.getNiche() != null) {
				sb.append("Niche: ").append(pocket.getNiche()).append("\n");
			}
			if (pocket.getProblem() != null) {
				sb.append("Buyer problem: ").append(pocket.getProblem()).append("\n");
			}
		}

		if (profile != null) {
			sb.append("\n=== CREATOR VOICE & AUDIENCE ===\n");
			if (profile.getAudience() != null && !profile.getAudience().isBlank()) {
				sb.append("Audience: ").append(profile.getAudience()).append("\n");
			}
			if (profile.getVoice() != null && !profile.getVoice().isBlank()) {
				sb.append("Voice: ").append(profile.getVoice()).append("\n");
			}
			if (profile.getTransformation() != null && !profile.getTransformation().isBlank()) {
				sb.append("Transformation: ").append(profile.getTransformation()).append("\n");
			}
		}

		if (allSections != null && !allSections.isEmpty()) {
			sb.append("\n=== FULL TABLE OF CONTENTS (for continuity) ===\n");
			for (int i = 0; i < allSections.size(); i++) {
				RelaySection s = allSections.get(i);
				sb.append(i + 1).append(". ").append(s.getTitle());
				if (i == sectionIndex) {
					sb.append("  <-- WRITING THIS SECTION NOW");
				}
				sb.append("\n");
			}
		}

		sb.append("\n=== SECTION TO WRITE (").append(sectionIndex + 1).append(" of ").append(totalSections).append(") ===\n");
		sb.append("Title: ").append(section.getTitle()).append("\n");
		if (section.getMoment() != null && !section.getMoment().isBlank()) {
			sb.append("Reader's moment: ").append(section.getMoment()).append("\n");
		}

		if (passages != null && !passages.isEmpty()) {
			sb.append("\n=== RETRIEVED SOURCE PASSAGES ===\n");
			int idx = 1;
			for (RetrievedPassage p : passages) {
				String snippet = p.text() != null ? p.text().trim() : "";
				if (snippet.length() > 900) {
					snippet = snippet.substring(0, 900) + "…";
				}
				sb.append("--- Source ").append(idx++)
						.append(" (").append(p.locator() != null ? p.locator() : p.sourceName()).append(") ---\n")
						.append(snippet).append("\n\n");
			}
		}

		return sb.toString();
	}

	private String lengthGuidanceForType(RelayProductType type) {
		if (type == null) {
			return "Aim for 500–800 words with actionable steps and a short exercise.";
		}
		return switch (type) {
			case EBOOK -> "Aim for 700–1,050 words of rich, structured teaching with concrete examples and a clear takeaway.";
			case WORKBOOK -> "Aim for 500–800 words: concise teaching followed by structured fill-in exercises and reflection prompts.";
			case PLANNER -> "Aim for 300–550 words: brief system setup plus clean fill-in planner/tracker prompts.";
			case CHECKLIST -> "Aim for 250–450 words: dense, scannable checklists with crisp one-line rationale per item.";
			case TEMPLATES -> "Aim for 400–700 words: ready-to-use copyable templates with [bracketed blanks] and brief usage tips.";
			case MINICOURSE -> "Aim for 500–800 words: focused lesson teaching, key takeaways, and a single concrete action step.";
		};
	}

	private String callGemini(String apiKey, String prompt, Long productId) {
		try {
			Client client = Client.builder().apiKey(apiKey).build();
			GenerateContentConfig config = GenerateContentConfig.builder()
					.candidateCount(1)
					.build();
			GenerateContentResponse response = client.models.generateContent(GEMINI_MODEL, prompt, config);
			String text = response.text();
			aiUsageMeter.recordGeminiChat(productId, 4, GEMINI_MODEL, response, prompt, text, "manuscript");
			if (text == null || text.isBlank()) {
				throw new BusinessRuleException("Gemini returned an empty manuscript section.");
			}
			return text.trim();
		}
		catch (BusinessRuleException ex) {
			throw ex;
		}
		catch (Exception ex) {
			log.error("Gemini manuscript writing failed: {}", ex.getMessage());
			throw new BusinessRuleException("Gemini manuscript writing failed: " + ex.getMessage());
		}
	}

	private String callOpenAiCompatible(String apiKey, String prompt, Long productId) {
		try {
			HttpClient httpClient = HttpClient.newBuilder()
					.connectTimeout(Duration.ofSeconds(20))
					.build();
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("model", OPENAI_MODEL);
			payload.put("messages", List.of(
					Map.of("role", "system", "content", "You are Relay, a skilled digital product writer. Respond with clean Markdown prose only."),
					Map.of("role", "user", "content", prompt)));

			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create("https://api.openai.com/v1/chat/completions"))
					.timeout(Duration.ofSeconds(90))
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
			aiUsageMeter.recordOpenAiChat(productId, 4, OPENAI_MODEL, root, prompt, content, "manuscript");
			if (content.isBlank()) {
				throw new BusinessRuleException("Text model returned an empty manuscript section.");
			}
			return content.trim();
		}
		catch (BusinessRuleException ex) {
			throw ex;
		}
		catch (Exception ex) {
			log.error("OpenAI manuscript writing failed: {}", ex.getMessage());
			throw new BusinessRuleException("Text model manuscript writing failed: " + ex.getMessage());
		}
	}

	private String stripOuterMarkdownFence(String text) {
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

