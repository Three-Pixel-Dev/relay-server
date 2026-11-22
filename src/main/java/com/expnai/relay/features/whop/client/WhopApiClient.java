package com.expnai.relay.features.whop.client;

import java.io.IOException;
import java.math.BigDecimal;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.expnai.relay.common.exception.BadGatewayException;
import com.expnai.relay.common.exception.BusinessRuleException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Thin client for the Whop REST API (v1). The API key is passed per call because every Relay user
 * connects their own Whop account. Keys are never logged.
 */
@Component
public class WhopApiClient {

	private static final Logger log = LoggerFactory.getLogger(WhopApiClient.class);
	private static final int ERROR_BODY_LIMIT = 300;

	private final ObjectMapper objectMapper;
	private final String baseUrl;
	private final String versionDate;
	private final Duration timeout;
	private final HttpClient http;

	@Autowired
	public WhopApiClient(
			ObjectMapper objectMapper,
			@Value("${relay.whop.api-base-url:https://api.whop.com/api/v1}") String baseUrl,
			@Value("${relay.whop.api-version-date:}") String versionDate,
			@Value("${relay.whop.timeout-seconds:30}") int timeoutSeconds) {
		this.objectMapper = objectMapper;
		this.baseUrl = stripTrailingSlash(baseUrl);
		this.versionDate = versionDate == null ? "" : versionDate.trim();
		this.timeout = Duration.ofSeconds(Math.max(5, timeoutSeconds));
		this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	}

	public record WhopAccount(String id, String title) {
	}

	public record WhopProduct(String id, String route) {
	}

	public record WhopPlan(String id, String purchaseUrl) {
	}

	public record WhopWebhook(String id, String secret) {
	}

	public WhopAccount getCurrentAccount(String apiKey) {
		JsonNode node = call("GET", "/accounts/me", apiKey, null);
		String id = text(node, "id");
		if (id == null) {
			throw new BadGatewayException("Whop did not return an account for this API key.");
		}
		String title = text(node, "title");
		if (title == null) {
			title = text(node, "business_name");
		}
		return new WhopAccount(id, title);
	}

	public WhopProduct createProduct(String apiKey, String title, String headline, String redirectPurchaseUrl,
			BigDecimal affiliatePercent) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("title", title);
		if (headline != null && !headline.isBlank()) {
			body.put("headline", headline);
		}
		body.put("visibility", "visible");
		if (redirectPurchaseUrl != null && !redirectPurchaseUrl.isBlank()) {
			body.put("redirect_purchase_url", redirectPurchaseUrl);
		}
		if (affiliatePercent != null && affiliatePercent.signum() > 0) {
			body.put("global_affiliate_status", "enabled");
			body.put("global_affiliate_percentage", affiliatePercent);
		}
		JsonNode node = call("POST", "/products", apiKey, body);
		String id = text(node, "id");
		if (id == null) {
			throw new BadGatewayException("Whop did not return a product id.");
		}
		return new WhopProduct(id, text(node, "route"));
	}

	public void updateProduct(String apiKey, String productId, Map<String, Object> fields) {
		call("PATCH", "/products/" + productId, apiKey, fields);
	}

	public WhopPlan createPlan(String apiKey, String productId, String title, BigDecimal price, String currency) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("product_id", productId);
		body.put("billing_type", "one_time");
		body.put("release_method", "buy_now");
		body.put("visibility", "visible");
		body.put("currency", currency.toLowerCase());
		body.put("initial_price", price);
		if (title != null && !title.isBlank()) {
			body.put("title", title.length() > 30 ? title.substring(0, 30) : title);
		}
		JsonNode node = call("POST", "/plans", apiKey, body);
		String id = text(node, "id");
		String purchaseUrl = text(node, "purchase_url");
		if (id == null || purchaseUrl == null) {
			throw new BadGatewayException("Whop did not return a checkout link for the plan.");
		}
		return new WhopPlan(id, purchaseUrl);
	}

	public WhopWebhook createWebhook(String apiKey, String url, String title, List<String> events) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("url", url);
		body.put("title", title);
		body.put("events", events);
		JsonNode node = call("POST", "/webhooks", apiKey, body);
		String id = text(node, "id");
		String secret = text(node, "webhook_secret");
		if (id == null || secret == null) {
			throw new BadGatewayException("Whop did not return a webhook signing secret.");
		}
		return new WhopWebhook(id, secret);
	}

	public void deleteWebhook(String apiKey, String webhookId) {
		call("DELETE", "/webhooks/" + webhookId, apiKey, null);
	}

	private JsonNode call(String method, String path, String apiKey, Object body) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new BusinessRuleException("Connect your Whop account first.");
		}
		try {
			HttpRequest.Builder builder = HttpRequest.newBuilder()
					.uri(URI.create(baseUrl + path))
					.timeout(timeout)
					.header("Authorization", "Bearer " + apiKey.trim())
					.header("Accept", "application/json");
			if (!versionDate.isEmpty()) {
				builder.header("Api-Version-Date", versionDate);
			}
			if (body != null) {
				builder.header("Content-Type", "application/json");
				builder.method(method, HttpRequest.BodyPublishers.ofString(
						objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
			}
			else {
				builder.method(method, HttpRequest.BodyPublishers.noBody());
			}
			HttpResponse<String> response = http.send(builder.build(),
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			int status = response.statusCode();
			if (status == 401 || status == 403) {
				throw new BusinessRuleException(
						"Whop rejected this API key. Check it is for the right account and has product, plan and webhook permissions.");
			}
			if (status < 200 || status >= 300) {
				log.warn("Whop {} {} failed with HTTP {}", method, path, status);
				throw new BadGatewayException("Whop returned an error (HTTP " + status + "): "
						+ errorMessage(response.body()));
			}
			String payload = response.body();
			if (payload == null || payload.isBlank()) {
				return objectMapper.createObjectNode();
			}
			return objectMapper.readTree(payload);
		}
		catch (BusinessRuleException | BadGatewayException ex) {
			throw ex;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new BadGatewayException("Request to Whop was interrupted.");
		}
		catch (IOException | RuntimeException ex) {
			log.warn("Whop {} {} failed: {}", method, path, ex.getClass().getSimpleName());
			throw new BadGatewayException("Could not reach Whop. Try again in a moment.");
		}
	}

	private String errorMessage(String body) {
		if (body == null || body.isBlank()) {
			return "no details";
		}
		try {
			JsonNode node = objectMapper.readTree(body);
			String message = text(node, "message");
			if (message == null && node.path("error").isObject()) {
				message = text(node.path("error"), "message");
			}
			if (message == null) {
				message = text(node, "error");
			}
			if (message != null) {
				return truncate(message);
			}
		}
		catch (IOException ignored) {
			// fall through to raw body
		}
		return truncate(body);
	}

	private static String truncate(String value) {
		String single = value.replaceAll("\\s+", " ").trim();
		return single.length() > ERROR_BODY_LIMIT ? single.substring(0, ERROR_BODY_LIMIT) + "…" : single;
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node == null ? null : node.get(field);
		if (value == null || value.isNull() || !value.isValueNode()) {
			return null;
		}
		String text = value.asText();
		return text == null || text.isBlank() ? null : text;
	}

	private static String stripTrailingSlash(String url) {
		String trimmed = url == null ? "" : url.trim();
		return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
	}
}
