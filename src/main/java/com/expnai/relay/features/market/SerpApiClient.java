package com.expnai.relay.features.market;

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
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.expnai.relay.features.market.MarketResearchPack.GoogleSearchResult;
import com.expnai.relay.features.market.MarketResearchPack.GoogleTrendsResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * SerpAPI client for Google organic search ({@code engine=google})
 * and Google Trends ({@code engine=google_trends}) using {@code SERPAPI_API_KEY}.
 */
@Service
@RequiredArgsConstructor
public class SerpApiClient {

	private final ObjectMapper objectMapper;

	@Value("${serpapi.api.key:}")
	private String serpApiKey;

	@Value("${relay.market.serpapi-base-url:https://serpapi.com/search.json}")
	private String serpApiBaseUrl;

	public boolean isConfigured() {
		return serpApiKey != null && !serpApiKey.isBlank();
	}

	public GoogleSearchResult searchGoogle(String keyword) {
		if (!isConfigured()) {
			throw new IllegalStateException("SERPAPI_API_KEY is not configured");
		}
		try {
			String url = serpApiBaseUrl
					+ "?engine=google"
					+ "&q=" + URLEncoder.encode(keyword.trim(), StandardCharsets.UTF_8)
					+ "&hl=en&gl=us&num=10"
					+ "&api_key=" + URLEncoder.encode(serpApiKey.trim(), StandardCharsets.UTF_8);

			JsonNode root = executeGetJson(url);
			long totalResults = root.path("search_information").path("total_results").asLong(0L);

			List<String> topTitles = new ArrayList<>();
			JsonNode organic = root.path("organic_results");
			if (organic.isArray()) {
				for (JsonNode item : organic) {
					String title = item.path("title").asText("").trim();
					if (!title.isEmpty() && topTitles.size() < 4) {
						topTitles.add(title.length() > 75 ? title.substring(0, 75) + "…" : title);
					}
				}
			}

			List<String> relatedQueries = new ArrayList<>();
			JsonNode related = root.path("related_searches");
			if (related.isArray()) {
				for (JsonNode item : related) {
					String q = item.path("query").asText("").trim();
					if (!q.isEmpty() && relatedQueries.size() < 5) {
						relatedQueries.add(q);
					}
				}
			}

			String queriedAt = LocalDate.now(ZoneOffset.UTC).toString();
			return new GoogleSearchResult(
					keyword.trim(),
					totalResults,
					List.copyOf(topTitles),
					List.copyOf(relatedQueries),
					queriedAt);
		}
		catch (IllegalStateException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalStateException("SerpAPI Google search failed for \"" + keyword + "\": " + ex.getMessage(), ex);
		}
	}

	public GoogleTrendsResult fetchGoogleTrends(String keyword) {
		if (!isConfigured()) {
			throw new IllegalStateException("SERPAPI_API_KEY is not configured");
		}
		try {
			String url = serpApiBaseUrl
					+ "?engine=google_trends"
					+ "&q=" + URLEncoder.encode(keyword.trim(), StandardCharsets.UTF_8)
					+ "&data_type=TIMESERIES"
					+ "&date=today+12-m"
					+ "&api_key=" + URLEncoder.encode(serpApiKey.trim(), StandardCharsets.UTF_8);

			JsonNode root = executeGetJson(url);
			JsonNode timeline = root.path("interest_over_time").path("timeline_data");

			List<Integer> values = new ArrayList<>();
			String firstDate = "";
			String lastDate = "";

			if (timeline.isArray()) {
				for (JsonNode point : timeline) {
					String dateStr = point.path("date").asText("").trim();
					if (firstDate.isEmpty() && !dateStr.isEmpty()) {
						firstDate = dateStr;
					}
					if (!dateStr.isEmpty()) {
						lastDate = dateStr;
					}
					JsonNode pointValues = point.path("values");
					if (pointValues.isArray() && !pointValues.isEmpty()) {
						int extracted = pointValues.get(0).path("extracted_value").asInt(-1);
						if (extracted >= 0) {
							values.add(extracted);
						}
					}
				}
			}

			if (values.isEmpty()) {
				throw new IllegalStateException("Google Trends returned no timeline points for \"" + keyword + "\"");
			}

			int sum = 0;
			int peak = 0;
			for (int v : values) {
				sum += v;
				if (v > peak) {
					peak = v;
				}
			}
			int avg = Math.round((float) sum / values.size());
			int latest = values.get(values.size() - 1);

			String direction = computeDirection(values);
			String dateRange = (!firstDate.isEmpty() && !lastDate.isEmpty())
					? firstDate + " – " + lastDate
					: "past 12 months";

			String queriedAt = LocalDate.now(ZoneOffset.UTC).toString();
			return new GoogleTrendsResult(
					keyword.trim(),
					avg,
					latest,
					peak,
					direction,
					dateRange,
					queriedAt);
		}
		catch (IllegalStateException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalStateException("SerpAPI Google Trends failed for \"" + keyword + "\": " + ex.getMessage(), ex);
		}
	}

	private JsonNode executeGetJson(String url) throws Exception {
		HttpClient http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(10))
				.build();
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.timeout(Duration.ofSeconds(20))
				.header("Accept", "application/json")
				.GET()
				.build();
		HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (resp.statusCode() >= 400) {
			throw new IllegalStateException("SerpAPI returned HTTP " + resp.statusCode());
		}
		JsonNode root = objectMapper.readTree(resp.body());
		if (root.has("error") && !root.path("error").asText("").isBlank()) {
			throw new IllegalStateException("SerpAPI error: " + root.path("error").asText());
		}
		return root;
	}

	private String computeDirection(List<Integer> values) {
		if (values.size() < 6) {
			return "steady";
		}
		int window = Math.max(3, values.size() / 4);
		double earlyAvg = averageSlice(values, 0, window);
		double recentAvg = averageSlice(values, values.size() - window, values.size());
		if (recentAvg >= earlyAvg + 8.0) {
			return "rising";
		}
		if (recentAvg <= earlyAvg - 8.0) {
			return "declining";
		}
		return "steady";
	}

	private double averageSlice(List<Integer> values, int startInclusive, int endExclusive) {
		int count = 0;
		int sum = 0;
		for (int i = startInclusive; i < endExclusive && i < values.size(); i++) {
			sum += values.get(i);
			count++;
		}
		return count == 0 ? 0.0 : (double) sum / count;
	}
}

