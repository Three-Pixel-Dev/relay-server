package com.expnai.relay.features.beehiiv;

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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * beehiiv API v2 client (Bearer API key or OAuth access token).
 */
@Component
@RequiredArgsConstructor
public class BeehiivClient {

	private static final Logger log = LoggerFactory.getLogger(BeehiivClient.class);
	private static final String API_BASE = "https://api.beehiiv.com/v2";

	private final ObjectMapper objectMapper;

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(12))
			.build();

	public record Publication(String id, String name) {
	}

	public void probeApiKey(String apiKey) {
		listPublications(apiKey);
	}

	public List<Publication> listPublications(String apiKey) {
		JsonNode root = get(apiKey, "/publications");
		List<Publication> out = new ArrayList<>();
		JsonNode data = root.get("data");
		if (data != null && data.isArray()) {
			for (JsonNode item : data) {
				out.add(new Publication(text(item, "id"), text(item, "name")));
			}
		}
		return out;
	}

	/**
	 * Creates a draft-ish record of the lead magnet plan via a custom note object in publication
	 * settings is not available — instead we verify the publication and return its public URL fields.
	 */
	public Map<String, Object> publicationSummary(String apiKey, String publicationId) {
		JsonNode root = get(apiKey, "/publications/" + publicationId);
		JsonNode data = root.has("data") ? root.get("data") : root;
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("id", text(data, "id"));
		summary.put("name", text(data, "name"));
		if (data.has("organization_name")) {
			summary.put("organization", text(data, "organization_name"));
		}
		JsonNode web = data.get("web_theme");
		if (web != null) {
			summary.put("theme", text(web, "primary_color"));
		}
		return summary;
	}

	private JsonNode get(String apiKey, String path) {
		try {
			HttpRequest req = HttpRequest.newBuilder()
					.uri(URI.create(API_BASE + path))
					.timeout(Duration.ofSeconds(20))
					.header("Authorization", "Bearer " + apiKey.trim())
					.header("Accept", "application/json")
					.GET()
					.build();
			HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
				log.warn("beehiiv GET {} HTTP {}", path, resp.statusCode());
				throw new BusinessRuleException(
						resp.statusCode() == 401 || resp.statusCode() == 403
								? "This key is not valid"
								: "beehiiv API error (HTTP " + resp.statusCode() + ").");
			}
			return objectMapper.readTree(resp.body());
		}
		catch (BusinessRuleException ex) {
			throw ex;
		}
		catch (Exception ex) {
			log.warn("beehiiv request failed: {}", ex.getMessage());
			throw new BusinessRuleException("This key is not valid");
		}
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
}
