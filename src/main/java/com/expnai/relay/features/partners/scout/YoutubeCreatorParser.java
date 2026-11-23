package com.expnai.relay.features.partners.scout;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/** Reads the JSON that YouTube embeds in its search and channel About pages. */
@Component
@RequiredArgsConstructor
public class YoutubeCreatorParser {

	private static final String DATA_MARKER = "ytInitialData = ";

	private final ObjectMapper objectMapper;

	public List<YoutubeChannelHit> parseSearch(String html) {
		List<YoutubeChannelHit> hits = new ArrayList<>();
		Optional<JsonNode> data = initialData(html);
		if (data.isEmpty()) {
			return hits;
		}
		List<JsonNode> renderers = new ArrayList<>();
		collect(data.get(), "channelRenderer", renderers);
		for (JsonNode node : renderers) {
			String path = node.path("navigationEndpoint").path("browseEndpoint").path("canonicalBaseUrl").asText("");
			if (path.isBlank()) {
				String channelId = node.path("channelId").asText("");
				path = channelId.isBlank() ? "" : "/channel/" + channelId;
			}
			String name = node.path("title").path("simpleText").asText("");
			if (path.isBlank() || name.isBlank()) {
				continue;
			}
			hits.add(new YoutubeChannelHit(path, name, subscribers(node), snippet(node.path("descriptionSnippet"))));
		}
		List<JsonNode> lockups = new ArrayList<>();
		collect(data.get(), "lockupViewModel", lockups);
		for (JsonNode node : lockups) {
			if ("LOCKUP_CONTENT_TYPE_CHANNEL".equals(node.path("contentType").asText(""))) {
				lockupChannel(node).ifPresent(hits::add);
			}
		}
		return hits;
	}

	/** YouTube serves some visitors a newer layout where channels come as view models. */
	private static Optional<YoutubeChannelHit> lockupChannel(JsonNode node) {
		JsonNode metadata = node.path("metadata").path("lockupMetadataViewModel");
		String name = metadata.path("title").path("content").asText("");
		List<JsonNode> paths = new ArrayList<>();
		collect(node, "canonicalBaseUrl", paths);
		String path = paths.isEmpty() ? "" : paths.get(0).asText("");
		if (path.isBlank()) {
			String channelId = node.path("contentId").asText("");
			path = channelId.isBlank() ? "" : "/channel/" + channelId;
		}
		if (name.isBlank() || path.isBlank()) {
			return Optional.empty();
		}
		String subscribers = "";
		String snippet = "";
		for (JsonNode row : metadata.path("metadata").path("contentMetadataViewModel").path("metadataRows")) {
			for (JsonNode part : row.path("metadataParts")) {
				String text = part.path("text").path("content").asText("");
				if (text.toLowerCase().contains("subscriber")) {
					subscribers = text;
				}
				else if (!text.isBlank() && !text.startsWith("@") && text.length() > snippet.length()) {
					snippet = text;
				}
			}
		}
		return Optional.of(new YoutubeChannelHit(path, name, subscribers, snippet));
	}

	public Optional<YoutubeChannelAbout> parseAbout(String html) {
		Optional<JsonNode> data = initialData(html);
		if (data.isEmpty()) {
			return Optional.empty();
		}
		String description = data.get().path("metadata").path("channelMetadataRenderer").path("description").asText("");
		List<JsonNode> aboutNodes = new ArrayList<>();
		collect(data.get(), "aboutChannelViewModel", aboutNodes);
		StringBuilder links = new StringBuilder();
		String subscribers = "";
		for (JsonNode about : aboutNodes) {
			if (description.isBlank()) {
				description = about.path("description").asText("");
			}
			if (subscribers.isBlank()) {
				subscribers = about.path("subscriberCountText").asText("");
			}
			for (JsonNode link : about.path("links")) {
				JsonNode model = link.path("channelExternalLinkViewModel");
				links.append(model.path("title").path("content").asText("")).append(' ')
						.append(model.path("link").path("content").asText("")).append('\n');
			}
		}
		if (description.isBlank() && links.length() == 0) {
			return Optional.empty();
		}
		return Optional.of(new YoutubeChannelAbout(description, links.toString().trim(), subscribers));
	}

	private Optional<JsonNode> initialData(String html) {
		if (html == null) {
			return Optional.empty();
		}
		int marker = html.indexOf(DATA_MARKER);
		if (marker < 0) {
			return Optional.empty();
		}
		int start = html.indexOf('{', marker);
		if (start < 0) {
			return Optional.empty();
		}
		try {
			// readTree parses the first JSON value and ignores whatever follows the closing brace.
			return Optional.of(objectMapper.readTree(html.substring(start)));
		}
		catch (Exception ex) {
			return Optional.empty();
		}
	}

	private static void collect(JsonNode node, String field, List<JsonNode> out) {
		if (node == null) {
			return;
		}
		if (node.isArray()) {
			for (JsonNode child : node) {
				collect(child, field, out);
			}
		}
		else if (node.isObject()) {
			node.fields().forEachRemaining(entry -> {
				if (field.equals(entry.getKey())) {
					out.add(entry.getValue());
				}
				else {
					collect(entry.getValue(), field, out);
				}
			});
		}
	}

	private static String subscribers(JsonNode renderer) {
		for (String field : new String[] { "videoCountText", "subscriberCountText" }) {
			String text = renderer.path(field).path("simpleText").asText("");
			if (text.toLowerCase().contains("subscriber")) {
				return text;
			}
		}
		return "";
	}

	private static String snippet(JsonNode snippetNode) {
		StringBuilder text = new StringBuilder();
		for (JsonNode run : snippetNode.path("runs")) {
			text.append(run.path("text").asText(""));
		}
		return text.toString().trim();
	}
}
