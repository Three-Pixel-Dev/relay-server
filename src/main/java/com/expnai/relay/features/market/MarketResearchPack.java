package com.expnai.relay.features.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Aggregated, dated market research evidence collected from Etsy Open API v3,
 * SerpAPI Google Search, and SerpAPI Google Trends for Build step 1 (Profitable pockets).
 */
public record MarketResearchPack(
		String queriedAt,
		List<String> keywords,
		List<EtsyKeywordResult> etsyResults,
		List<GoogleSearchResult> googleResults,
		List<GoogleTrendsResult> trendsResults,
		List<String> missingProviders
) {
	public record EtsyKeywordResult(
			String keyword,
			int activeListingsCount,
			BigDecimal medianPrice,
			BigDecimal minPrice,
			BigDecimal maxPrice,
			String currency,
			List<String> sampleTitles,
			String queriedAt,
			String source
	) {
		public String sourceLabel() {
			return "apify".equalsIgnoreCase(source) ? "Etsy via Apify" : "Etsy Open API";
		}
	}

	public record GoogleSearchResult(
			String keyword,
			long totalResults,
			List<String> topTitles,
			List<String> relatedQueries,
			String queriedAt
	) {
	}

	public record GoogleTrendsResult(
			String keyword,
			int avgInterest,
			int latestInterest,
			int peakInterest,
			String trendDirection,
			String dateRange,
			String queriedAt
	) {
	}

	public boolean isEmpty() {
		return etsyResults.isEmpty() && googleResults.isEmpty() && trendsResults.isEmpty();
	}

	public String toPromptText() {
		StringBuilder sb = new StringBuilder();
		sb.append("Market Research Pack (queried_at: ").append(queriedAt).append(")\n");
		sb.append("Keywords researched: ").append(String.join(", ", keywords)).append("\n\n");

		if (!etsyResults.isEmpty()) {
			sb.append("=== ETSY MARKETPLACE (Active Listings & Prices) ===\n");
			for (EtsyKeywordResult r : etsyResults) {
				sb.append("- ").append(r.sourceLabel()).append(" (").append(r.queriedAt()).append(") keyword \"")
						.append(r.keyword()).append("\": ")
						.append(r.activeListingsCount()).append(" active listings");
				if (r.medianPrice() != null) {
					sb.append(", median price ").append(formatPrice(r.medianPrice(), r.currency()));
					if (r.minPrice() != null && r.maxPrice() != null) {
						sb.append(" (range ").append(formatPrice(r.minPrice(), r.currency()))
								.append("–").append(formatPrice(r.maxPrice(), r.currency())).append(")");
					}
				}
				if (!r.sampleTitles().isEmpty()) {
					sb.append("; top listings: ").append(String.join(" | ", r.sampleTitles()));
				}
				sb.append("\n");
			}
			sb.append("\n");
		}

		if (!googleResults.isEmpty()) {
			sb.append("=== GOOGLE / SERPAPI (Web Demand & Related Queries) ===\n");
			for (GoogleSearchResult r : googleResults) {
				sb.append("- Google / SerpAPI (").append(r.queriedAt()).append(") keyword \"").append(r.keyword()).append("\": ");
				if (r.totalResults() > 0) {
					sb.append("~").append(String.format("%,d", r.totalResults())).append(" results");
				}
				else {
					sb.append("organic results found");
				}
				if (!r.relatedQueries().isEmpty()) {
					sb.append("; related searches: ").append(String.join(", ", r.relatedQueries()));
				}
				if (!r.topTitles().isEmpty()) {
					sb.append("; top pages: ").append(String.join(" | ", r.topTitles()));
				}
				sb.append("\n");
			}
			sb.append("\n");
		}

		if (!trendsResults.isEmpty()) {
			sb.append("=== GOOGLE TRENDS VIA SERPAPI (Relative Search Interest 0-100, NOT monthly search volume) ===\n");
			for (GoogleTrendsResult r : trendsResults) {
				sb.append("- Google Trends (").append(r.queriedAt())
						.append(r.dateRange() != null && !r.dateRange().isBlank() ? ", " + r.dateRange() : "")
						.append(") keyword \"").append(r.keyword()).append("\": relative interest avg ")
						.append(r.avgInterest()).append("/100, latest ").append(r.latestInterest())
						.append("/100, peak ").append(r.peakInterest()).append("/100 (")
						.append(r.trendDirection()).append(")\n");
			}
			sb.append("\n");
		}

		if (!missingProviders.isEmpty()) {
			sb.append("=== UNAVAILABLE / SKIPPED PROVIDERS (do NOT invent data for these) ===\n");
			for (String missing : missingProviders) {
				sb.append("- ").append(missing).append("\n");
			}
		}

		return sb.toString().trim();
	}

	private static String formatPrice(BigDecimal price, String currency) {
		String sym = "USD".equalsIgnoreCase(currency) || currency == null || currency.isBlank()
				? "$"
				: currency + " ";
		return sym + price.setScale(2, RoundingMode.HALF_UP);
	}
}

