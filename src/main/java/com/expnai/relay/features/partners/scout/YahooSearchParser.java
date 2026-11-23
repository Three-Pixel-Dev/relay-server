package com.expnai.relay.features.partners.scout;

import java.util.ArrayList;
import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/** Extracts organic results (link, title, snippet) from a Yahoo web search page. */
@Component
public class YahooSearchParser {

	public List<WebSearchHit> parse(String html) {
		List<WebSearchHit> hits = new ArrayList<>();
		if (html == null || html.isBlank()) {
			return hits;
		}
		Document document = Jsoup.parse(html);
		for (Element result : document.select("div.algo")) {
			Element link = result.selectFirst("a[href^=http]");
			if (link == null) {
				continue;
			}
			Element title = result.selectFirst("h3");
			Element snippet = result.selectFirst("div.compText p, div.compText");
			hits.add(new WebSearchHit(
					link.attr("href"),
					title == null ? "" : title.text(),
					snippet == null ? "" : snippet.text()));
		}
		return hits;
	}

	public boolean looksLikeResultsPage(String html) {
		return html != null && html.contains("id=\"web\"");
	}
}
