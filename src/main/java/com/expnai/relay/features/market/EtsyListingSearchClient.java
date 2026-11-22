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
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.expnai.relay.features.market.MarketResearchPack.EtsyKeywordResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * Etsy Open API v3 active listing search client using app {@code x-api-key} header only
 * (no seller OAuth required for market research).
 */
@Service
@RequiredArgsConstructor
public class EtsyListingSearchClient {

	private static final int SAMPLE_LIMIT = 25;

	private final ObjectMapper objectMapper;

	@Value("${etsy.api.key:}")
	private String etsyApiKey;

	@Value("${relay.market.etsy-base-url:https://openapi.etsy.com/v3/application}")
	private String etsyBaseUrl;

	public boolean isConfigured() {
		return etsyApiKey != null && !etsyApiKey.isBlank();
	}

	public EtsyKeywordResult searchActiveListings(String keyword) {
		if (!isConfigured()) {
			throw new IllegalStateException("ETSY_API_KEY is not configured");
		}
		try {
			String base = etsyBaseUrl.replaceAll("/+$", "");
			String url = base + "/listings/active?keywords="
					+ URLEncoder.encode(keyword.trim(), StandardCharsets.UTF_8)
					+ "&limit=" + SAMPLE_LIMIT
					+ "&sort_on=score";

			HttpClient http = HttpClient.newBuilder()
					.connectTimeout(Duration.ofSeconds(10))
					.build();
			HttpRequest req = HttpRequest.newBuilder()
					.uri(URI.create(url))
					.timeout(Duration.ofSeconds(15))
					.header("x-api-key", etsyApiKey.trim())
					.header("Accept", "application/json")
					.GET()
					.build();

			HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (resp.statusCode() >= 400) {
				throw new IllegalStateException("Etsy API returned HTTP " + resp.statusCode());
			}

			JsonNode root = objectMapper.readTree(resp.body());
			int count = root.path("count").asInt(0);
			JsonNode results = root.path("results");

			List<BigDecimal> prices = new ArrayList<>();
			List<String> sampleTitles = new ArrayList<>();
			String currency = "USD";

			if (results.isArray()) {
				for (JsonNode item : results) {
					String title = item.path("title").asText("").trim();
					if (!title.isEmpty() && sampleTitles.size() < 3) {
						sampleTitles.add(title.length() > 70 ? title.substring(0, 70) + "…" : title);
					}
					JsonNode priceNode = item.path("price");
					if (!priceNode.isMissingNode() && priceNode.has("amount") && priceNode.has("divisor")) {
						long amount = priceNode.path("amount").asLong(0);
						long divisor = priceNode.path("divisor").asLong(100);
						if (amount > 0 && divisor > 0) {
							BigDecimal price = BigDecimal.valueOf(amount)
									.divide(BigDecimal.valueOf(divisor), 2, RoundingMode.HALF_UP);
							prices.add(price);
							String code = priceNode.path("currency_code").asText("");
							if (!code.isBlank()) {
								currency = code;
							}
						}
					}
				}
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

			String queriedAt = LocalDate.now(ZoneOffset.UTC).toString();
			return new EtsyKeywordResult(
					keyword.trim(),
					count,
					medianPrice,
					minPrice,
					maxPrice,
					currency,
					List.copyOf(sampleTitles),
					queriedAt,
					"etsy_api");
		}
		catch (IllegalStateException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalStateException("Etsy listing search failed for \"" + keyword + "\": " + ex.getMessage(), ex);
		}
	}
}

