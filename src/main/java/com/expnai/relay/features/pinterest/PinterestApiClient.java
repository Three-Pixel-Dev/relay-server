package com.expnai.relay.features.pinterest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.expnai.relay.common.exception.BusinessRuleException;
import com.expnai.relay.features.pinterest.config.PinterestProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PinterestApiClient {

	private static final Logger log = LoggerFactory.getLogger(PinterestApiClient.class);

	private final PinterestProperties properties;
	private final ObjectMapper objectMapper;

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(15))
			.build();

	public record UserAccount(String username, String accountType) {
	}

	public record Board(String id, String name) {
	}

	public record PinResult(String id) {
	}

	public record AdAccount(String id, String name) {
	}

	public UserAccount getUserAccount(String accessToken) {
		JsonNode root = get(accessToken, "/user_account");
		return new UserAccount(
				text(root, "username"),
				text(root, "account_type"));
	}

	public List<Board> listBoards(String accessToken) {
		JsonNode root = get(accessToken, "/boards?page_size=25");
		List<Board> out = new ArrayList<>();
		JsonNode items = root.get("items");
		if (items != null && items.isArray()) {
			for (JsonNode item : items) {
				out.add(new Board(text(item, "id"), text(item, "name")));
			}
		}
		return out;
	}

	public Board createBoard(String accessToken, String name, String description) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", name);
		if (description != null && !description.isBlank()) {
			body.put("description", description);
		}
		JsonNode root = post(accessToken, "/boards", body);
		return new Board(text(root, "id"), text(root, "name"));
	}

	public PinResult createPin(
			String accessToken,
			String boardId,
			String title,
			String description,
			String link,
			String imageUrl) {
		Map<String, Object> media = new LinkedHashMap<>();
		media.put("source_type", "image_url");
		media.put("url", imageUrl);
		media.put("is_standard", true);

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("board_id", boardId);
		body.put("title", truncate(title, 100));
		if (description != null && !description.isBlank()) {
			body.put("description", truncate(description, 500));
		}
		if (link != null && !link.isBlank()) {
			body.put("link", link);
		}
		body.put("media_source", media);

		JsonNode root = post(accessToken, "/pins", body);
		return new PinResult(text(root, "id"));
	}

	public List<AdAccount> listAdAccounts(String accessToken) {
		JsonNode root = get(accessToken, "/ad_accounts?page_size=25");
		List<AdAccount> out = new ArrayList<>();
		JsonNode items = root.get("items");
		if (items != null && items.isArray()) {
			for (JsonNode item : items) {
				out.add(new AdAccount(text(item, "id"), text(item, "name")));
			}
		}
		return out;
	}

	/**
	 * Creates a CONSIDERATION campaign with fixed daily + lifetime spend caps (microcurrency).
	 * Returns campaign id, or null if ads API rejects (trial apps / missing billing).
	 */
	public String createCappedCampaign(
			String accessToken,
			String adAccountId,
			String name,
			long dailyBudgetMicros,
			long lifetimeBudgetMicros) {
		Map<String, Object> campaign = new LinkedHashMap<>();
		campaign.put("ad_account_id", adAccountId);
		campaign.put("name", truncate(name, 120));
		campaign.put("objective_type", "CONSIDERATION");
		campaign.put("status", "PAUSED"); // never spend until operator enables; Relay enforces caps
		campaign.put("daily_spend_cap", dailyBudgetMicros);
		campaign.put("lifetime_spend_cap", lifetimeBudgetMicros);
		campaign.put("is_flexible_daily_budgets", false);

		Map<String, Object> body = Map.of("campaigns", List.of(campaign));
		try {
			JsonNode root = post(accessToken, "/ad_accounts/" + adAccountId + "/campaigns", body);
			JsonNode items = root.get("items");
			if (items != null && items.isArray() && !items.isEmpty()) {
				JsonNode first = items.get(0);
				JsonNode data = first.get("data");
				if (data != null) {
					return text(data, "id");
				}
				return text(first, "id");
			}
			return text(root, "id");
		}
		catch (BusinessRuleException ex) {
			log.warn("Pinterest ads campaign create failed (caps still recorded locally): {}", ex.getMessage());
			return null;
		}
	}

	public void pauseCampaign(String accessToken, String adAccountId, String campaignId) {
		Map<String, Object> patch = new LinkedHashMap<>();
		patch.put("id", campaignId);
		patch.put("ad_account_id", adAccountId);
		patch.put("status", "PAUSED");
		Map<String, Object> body = Map.of("campaigns", List.of(patch));
		try {
			post(accessToken, "/ad_accounts/" + adAccountId + "/campaigns", body);
		}
		catch (BusinessRuleException ex) {
			log.warn("Could not pause Pinterest campaign {}: {}", campaignId, ex.getMessage());
		}
	}

	/** Returns lifetime spend in micros if available; null when unknown. */
	public Long getCampaignLifetimeSpendMicros(String accessToken, String adAccountId, String campaignId) {
		try {
			JsonNode root = get(accessToken,
					"/ad_accounts/" + adAccountId + "/campaigns/" + campaignId);
			if (root.has("lifetime_spend_cap")) {
				// Prefer analytics when present
			}
			JsonNode spend = root.get("lifetime_spend");
			if (spend != null && spend.isNumber()) {
				return spend.asLong();
			}
			JsonNode summary = root.get("summary_status");
			return null;
		}
		catch (Exception ex) {
			log.debug("Could not read campaign spend: {}", ex.getMessage());
			return null;
		}
	}

	private JsonNode get(String accessToken, String path) {
		try {
			HttpRequest req = HttpRequest.newBuilder()
					.uri(URI.create(properties.getApiBaseUrl() + path))
					.timeout(Duration.ofSeconds(30))
					.header("Authorization", "Bearer " + accessToken)
					.header("Accept", "application/json")
					.GET()
					.build();
			HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
			return parse(resp, path);
		}
		catch (BusinessRuleException ex) {
			throw ex;
		}
		catch (Exception ex) {
			log.warn("Pinterest GET {} failed: {}", path, ex.getMessage());
			throw new BusinessRuleException("Pinterest API request failed.");
		}
	}

	private JsonNode post(String accessToken, String path, Object body) {
		try {
			String json = objectMapper.writeValueAsString(body);
			HttpRequest req = HttpRequest.newBuilder()
					.uri(URI.create(properties.getApiBaseUrl() + path))
					.timeout(Duration.ofSeconds(45))
					.header("Authorization", "Bearer " + accessToken)
					.header("Content-Type", "application/json")
					.header("Accept", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(json))
					.build();
			HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
			return parse(resp, path);
		}
		catch (BusinessRuleException ex) {
			throw ex;
		}
		catch (Exception ex) {
			log.warn("Pinterest POST {} failed: {}", path, ex.getMessage());
			throw new BusinessRuleException("Pinterest API request failed.");
		}
	}

	private JsonNode parse(HttpResponse<String> resp, String path) throws Exception {
		if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
			log.warn("Pinterest {} HTTP {}: {}", path, resp.statusCode(), truncate(resp.body()));
			throw new BusinessRuleException(mapError(resp.statusCode(), resp.body()));
		}
		if (resp.body() == null || resp.body().isBlank()) {
			return objectMapper.createObjectNode();
		}
		return objectMapper.readTree(resp.body());
	}

	private static String mapError(int status, String body) {
		if (status == 401 || status == 403) {
			return "Pinterest rejected the token. Reconnect in Connections.";
		}
		if (status == 429) {
			return "Pinterest rate limit hit. Try again shortly.";
		}
		return "Pinterest API error (HTTP " + status + ").";
	}

	private static String text(JsonNode node, String field) {
		if (node == null) {
			return null;
		}
		JsonNode child = node.get(field);
		if (child == null || child.isNull()) {
			return null;
		}
		String v = child.asText(null);
		return v != null && !v.isBlank() ? v : null;
	}

	private static String truncate(String value, int max) {
		if (value == null) {
			return "";
		}
		return value.length() <= max ? value : value.substring(0, max - 1) + "…";
	}

	private static String truncate(String body) {
		if (body == null) {
			return "";
		}
		return body.length() > 400 ? body.substring(0, 400) + "…" : body;
	}
}
