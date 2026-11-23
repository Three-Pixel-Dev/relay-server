package com.expnai.relay.features.partners.scout;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/** Extracts organic results from DuckDuckGo's HTML endpoint (used as a Yahoo fallback). */
@Component
public class DuckDuckGoSearchParser {

	public List<WebSearchHit> parse(String html) {
		List<WebSearchHit> hits = new ArrayList<>();
		if (html == null || html.isBlank()) {
			return hits;
		}
		Document document = Jsoup.parse(html);
		for (Element result : document.select("div.result, div.web-result, article[data-testid=result]")) {
			Element link = result.selectFirst("a.result__a, a[data-testid=result-title-a], h2 a[href]");
			if (link == null) {
				continue;
			}
			String href = unwrapRedirect(link.attr("href"));
			if (href.isBlank() || !href.startsWith("http")) {
				continue;
			}
			Element snippet = result.selectFirst(
					"a.result__snippet, .result__snippet, [data-result=snippet], .result-snippet");
			hits.add(new WebSearchHit(
					href,
					link.text() == null ? "" : link.text().trim(),
					snippet == null ? "" : snippet.text().trim()));
		}
		return hits;
	}

	public boolean looksLikeResultsPage(String html) {
		if (html == null || html.isBlank()) {
			return false;
		}
		String lower = html.toLowerCase(Locale.ROOT);
		return lower.contains("result__a")
				|| lower.contains("web-result")
				|| lower.contains("data-testid=\"result\"")
				|| lower.contains("duckduckgo");
	}

	private static String unwrapRedirect(String href) {
		if (href == null || href.isBlank()) {
			return "";
		}
		String trimmed = href.trim();
		if (!trimmed.contains("uddg=")) {
			return trimmed;
		}
		try {
			URI uri = URI.create(trimmed.startsWith("//") ? "https:" + trimmed : trimmed);
			String query = uri.getRawQuery();
			if (query == null) {
				return trimmed;
			}
			for (String part : query.split("&")) {
				int eq = part.indexOf('=');
				if (eq <= 0) {
					continue;
				}
				if (!"uddg".equals(part.substring(0, eq))) {
					continue;
				}
				return URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
			}
		}
		catch (IllegalArgumentException ignored) {
			return trimmed;
		}
		return trimmed;
	}
}
