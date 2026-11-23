package com.expnai.relay.features.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.expnai.relay.common.logging.RelayTrace;
import com.expnai.relay.features.market.MarketResearchPack.EtsyKeywordResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * Apify Etsy search scraper client ({@code themineworks/etsy-search-scraper}) used as an
 * env-switchable marketplace provider or fallback when the Etsy Open API is unavailable.
 */
@Service
@RequiredArgsConstructor
public class ApifyEtsySearchClient {

	private static final int SAMPLE_LIMIT = 12;
	private static final String APIFY_BASE_URL = "https://api.apify.com/v2/acts/";

	private final ObjectMapper objectMapper;

	@Value("${apify.token:}")
	private String apifyToken;

	@Value("${apify.etsy-actor-id:themineworks~etsy-search-scraper}")
	private String actorId;

	@Value("${relay.market.apify-timeout-seconds:120}")
	private int timeoutSeconds;

	public boolean isConfigured() {
		return apifyToken != null && !apifyToken.isBlank();
	}

	public EtsyKeywordResult searchActiveListings(String keyword) {
		if (!isConfigured()) {
			throw new IllegalStateException("APIFY_TOKEN is not configured");
		}
		String cleanKeyword = keyword != null ? keyword.trim() : "";
		if (cleanKeyword.isEmpty()) {
			throw new IllegalStateException("Keyword is blank");
		}
		try {
			String normalizedActor = (actorId != null && !actorId.isBlank())
					? actorId.trim().replace('/', '~')
					: "themineworks~etsy-search-scraper";
			RelayTrace.scrape("apify etsy start actor={} keyword='{}' maxItems={}",
					normalizedActor, cleanKeyword, SAMPLE_LIMIT);
			String url = APIFY_BASE_URL
					+ URLEncoder.encode(normalizedActor, StandardCharsets.UTF_8)
					+ "/run-sync-get-dataset-items?token="
					+ URLEncoder.encode(apifyToken.trim(), StandardCharsets.UTF_8);

			Map<String, Object> input = new LinkedHashMap<>();
			input.put("queries", List.of(cleanKeyword));
			input.put("maxItems", SAMPLE_LIMIT);
			input.put("sort", "relevance");

			int effectiveTimeout = Math.max(15, timeoutSeconds);
			HttpClient http = HttpClient.newBuilder()
					.connectTimeout(Duration.ofSeconds(15))
					.build();
			HttpRequest req = HttpRequest.newBuilder()
					.uri(URI.create(url))
					.timeout(Duration.ofSeconds(effectiveTimeout))
					.header("Content-Type", "application/json")
					.header("Accept", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(
							objectMapper.writeValueAsString(input), StandardCharsets.UTF_8))
					.build();

			HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
				throw new IllegalStateException("Apify returned HTTP " + resp.statusCode());
			}

			JsonNode root = objectMapper.readTree(resp.body());
			if (!root.isArray()) {
				throw new IllegalStateException("Apify returned unexpected non-array dataset payload");
			}

			List<BigDecimal> prices = new ArrayList<>();
			List<String> sampleTitles = new ArrayList<>();
			String currency = "USD";
			int listingCount = 0;
			int totalResults = 0;

			for (JsonNode item : root) {
				if (item == null || !item.isObject()) {
					continue;
				}
				int itemTotal = extractPositiveInt(item, "total_results", "totalResults");
				if (itemTotal > totalResults) {
					totalResults = itemTotal;
				}

				// Skip summary/info rows marked with _type
				String rowType = item.path("_type").asText("").trim();
				if (!rowType.isEmpty()) {
					continue;
				}

				String title = item.path("title").asText("").trim();
				if (title.isEmpty()) {
					title = item.path("name").asText("").trim();
				}
				BigDecimal price = parsePrice(item.path("price"));
				if (title.isEmpty() && price == null) {
					continue;
				}

				listingCount++;
				if (!title.isEmpty() && sampleTitles.size() < 3) {
					sampleTitles.add(title.length() > 70 ? title.substring(0, 70) + "…" : title);
				}
				if (price != null && price.signum() > 0) {
					prices.add(price.setScale(2, RoundingMode.HALF_UP));
					String itemCurrency = extractCurrency(item);
					if (itemCurrency != null && !itemCurrency.isBlank()) {
						currency = itemCurrency;
					}
				}
			}

			if (listingCount == 0 && totalResults == 0) {
				throw new IllegalStateException("Apify returned no listings for \"" + cleanKeyword + "\"");
			}

			BigDecimal medianPrice = null;
			BigDecimal minPrice = null;
			BigDecimal maxPrice = null;
			if (!prices.isEmpty()) {
				Collections.sort(prices);
				minPrice = prices.get(0);
				maxPrice = prices.get(prices.size() - 1);
				int mid = prices.size() / 2;
				if (prices.size() % 2 == 1) {
					medianPrice = prices.get(mid);
				}
				else {
					medianPrice = prices.get(mid - 1).add(prices.get(mid))
							.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
				}
			}

			int finalCount = totalResults > 0 ? totalResults : listingCount;
			String queriedAt = LocalDate.now(ZoneOffset.UTC).toString();
			RelayTrace.scrape("apify etsy done keyword='{}' listings={} sampled={} medianPrice={} {}",
					cleanKeyword, finalCount, listingCount, medianPrice, currency);
			return new EtsyKeywordResult(
					cleanKeyword,
					finalCount,
					medianPrice,
					minPrice,
					maxPrice,
					currency,
					List.copyOf(sampleTitles),
					queriedAt,
					"apify");
		}
		catch (IllegalStateException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalStateException("Apify Etsy search failed for \"" + cleanKeyword + "\": " + ex.getMessage(), ex);
		}
	}

	private int extractPositiveInt(JsonNode node, String... fieldNames) {
		for (String field : fieldNames) {
			JsonNode val = node.path(field);
			if (val.isInt() || val.isLong() || val.isNumber()) {
				int n = val.asInt(0);
				if (n > 0) {
					return n;
				}
			}
			else if (val.isTextual()) {
				String digits = val.asText("").replaceAll("[^0-9]", "");
				if (!digits.isEmpty()) {
					try {
						int n = Integer.parseInt(digits);
						if (n > 0) {
							return n;
						}
					}
					catch (NumberFormatException ignored) {
					}
				}
			}
		}
		return 0;
	}

	private BigDecimal parsePrice(JsonNode priceNode) {
		if (priceNode == null || priceNode.isMissingNode() || priceNode.isNull()) {
			return null;
		}
		if (priceNode.isNumber()) {
			return priceNode.decimalValue();
		}
		if (priceNode.isTextual()) {
			String cleaned = priceNode.asText("").replaceAll("[^0-9.]", "");
			if (!cleaned.isEmpty()) {
				try {
					return new BigDecimal(cleaned);
				}
				catch (NumberFormatException ignored) {
				}
			}
			return null;
		}
		if (priceNode.isObject()) {
			if (priceNode.has("amount") && priceNode.has("divisor")) {
				long amount = priceNode.path("amount").asLong(0);
				long divisor = priceNode.path("divisor").asLong(100);
				if (amount > 0 && divisor > 0) {
					return BigDecimal.valueOf(amount)
							.divide(BigDecimal.valueOf(divisor), 2, RoundingMode.HALF_UP);
				}
			}
			if (priceNode.has("value")) {
				return parsePrice(priceNode.path("value"));
			}
		}
		return null;
	}

	private String extractCurrency(JsonNode item) {
		for (String key : List.of("currency", "currency_code", "currencyCode")) {
			String val = item.path(key).asText("").trim();
			if (!val.isEmpty()) {
				return val;
			}
		}
		JsonNode priceNode = item.path("price");
		if (priceNode.isObject()) {
			for (String key : List.of("currency", "currency_code", "currencyCode")) {
				String val = priceNode.path(key).asText("").trim();
				if (!val.isEmpty()) {
					return val;
				}
			}
		}
		return null;
	}
}

