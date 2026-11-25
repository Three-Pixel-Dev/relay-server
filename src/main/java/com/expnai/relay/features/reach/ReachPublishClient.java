package com.expnai.relay.features.reach;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.expnai.relay.common.exception.BusinessRuleException;
import com.expnai.relay.features.reach.ReachTokenStore.Tokens;
import com.expnai.relay.features.reach.config.ReachProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.googleapis.auth.oauth2.GoogleRefreshTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;

import lombok.RequiredArgsConstructor;

/**
 * Posts one Short/Reel/TikTok from a public https video URL to a connected reach account.
 */
@Component
@RequiredArgsConstructor
public class ReachPublishClient {

	private static final Logger log = LoggerFactory.getLogger(ReachPublishClient.class);

	private final ReachProperties properties;
	private final ObjectMapper objectMapper;

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(20))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	public String publishYouTube(Tokens tokens, String title, String description, String videoUrl) {
		try {
			String access = ensureYoutubeAccess(tokens);
			byte[] bytes = downloadBytes(videoUrl);

			Map<String, Object> snippet = new LinkedHashMap<>();
			snippet.put("title", truncate(title, 90));
			snippet.put("description", description != null ? description : "");
			snippet.put("categoryId", "22");
			Map<String, Object> status = Map.of(
					"privacyStatus", "public",
					"selfDeclaredMadeForKids", false);
			Map<String, Object> resource = Map.of("snippet", snippet, "status", status);
			String metaJson = objectMapper.writeValueAsString(resource);

			HttpRequest init = HttpRequest.newBuilder()
					.uri(URI.create(
							"https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status"))
					.timeout(Duration.ofSeconds(60))
					.header("Authorization", "Bearer " + access)
					.header("Content-Type", "application/json; charset=UTF-8")
					.header("X-Upload-Content-Length", String.valueOf(bytes.length))
					.header("X-Upload-Content-Type", "video/mp4")
					.POST(HttpRequest.BodyPublishers.ofString(metaJson))
					.build();
			HttpResponse<String> initResp = http.send(init, HttpResponse.BodyHandlers.ofString());
			if (initResp.statusCode() < 200 || initResp.statusCode() >= 300) {
				log.warn("YouTube resumable init HTTP {}: {}", initResp.statusCode(), truncate(initResp.body(), 300));
				throw new BusinessRuleException("YouTube upload init failed (HTTP " + initResp.statusCode() + ").");
			}
			String uploadUrl = initResp.headers().firstValue("location")
					.orElseThrow(() -> new BusinessRuleException("YouTube did not return an upload URL."));

			HttpRequest upload = HttpRequest.newBuilder()
					.uri(URI.create(uploadUrl))
					.timeout(Duration.ofMinutes(10))
					.header("Authorization", "Bearer " + access)
					.header("Content-Type", "video/mp4")
					.header("Content-Length", String.valueOf(bytes.length))
					.PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
					.build();
			HttpResponse<String> uploadResp = http.send(upload, HttpResponse.BodyHandlers.ofString());
			if (uploadResp.statusCode() < 200 || uploadResp.statusCode() >= 300) {
				throw new BusinessRuleException("YouTube upload failed (HTTP " + uploadResp.statusCode() + ").");
			}
			JsonNode root = objectMapper.readTree(uploadResp.body());
			String id = text(root, "id");
			return id != null ? id : "ok";
		}
		catch (BusinessRuleException ex) {
			throw ex;
		}
		catch (Exception ex) {
			log.warn("YouTube upload failed: {}", ex.getMessage());
			throw new BusinessRuleException("YouTube upload failed: " + ex.getMessage());
		}
	}

	public String publishTikTok(Tokens tokens, String title, String videoUrl) {
		try {
			Map<String, Object> postInfo = new LinkedHashMap<>();
			postInfo.put("title", truncate(title, 150));
			postInfo.put("privacy_level", "PUBLIC_TO_EVERYONE");
			postInfo.put("disable_duet", false);
			postInfo.put("disable_comment", false);
			postInfo.put("disable_stitch", false);

			Map<String, Object> sourceInfo = new LinkedHashMap<>();
			sourceInfo.put("source", "PULL_FROM_URL");
			sourceInfo.put("video_url", videoUrl);

			Map<String, Object> body = new LinkedHashMap<>();
			body.put("post_info", postInfo);
			body.put("source_info", sourceInfo);

			String json = objectMapper.writeValueAsString(body);
			HttpRequest req = HttpRequest.newBuilder()
					.uri(URI.create("https://open.tiktokapis.com/v2/post/publish/video/init/"))
					.timeout(Duration.ofSeconds(45))
					.header("Authorization", "Bearer " + tokens.accessToken())
					.header("Content-Type", "application/json; charset=UTF-8")
					.POST(HttpRequest.BodyPublishers.ofString(json))
					.build();
			HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
				log.warn("TikTok publish HTTP {}: {}", resp.statusCode(), truncate(resp.body(), 300));
				throw new BusinessRuleException("TikTok publish failed (HTTP " + resp.statusCode()
						+ "). Domain may need verification for PULL_FROM_URL.");
			}
			JsonNode root = objectMapper.readTree(resp.body());
			JsonNode data = root.has("data") ? root.get("data") : root;
			String publishId = text(data, "publish_id");
			if (publishId == null) {
				publishId = text(data, "share_id");
			}
			return publishId != null ? publishId : "ok";
		}
		catch (BusinessRuleException ex) {
			throw ex;
		}
		catch (Exception ex) {
			log.warn("TikTok publish failed: {}", ex.getMessage());
			throw new BusinessRuleException("TikTok publish failed: " + ex.getMessage());
		}
	}

	public String publishInstagram(Tokens tokens, String caption, String videoUrl) {
		if (tokens.igUserId() == null || tokens.pageAccessToken() == null) {
			throw new BusinessRuleException(
					"Instagram connection is missing Page/IG ids. Reconnect Instagram.");
		}
		try {
			String v = properties.getInstagramGraphVersion();
			String createUrl = "https://graph.facebook.com/" + v + "/" + tokens.igUserId() + "/media"
					+ "?media_type=REELS"
					+ "&video_url=" + enc(videoUrl)
					+ "&caption=" + enc(caption != null ? caption : "")
					+ "&share_to_feed=true"
					+ "&access_token=" + enc(tokens.pageAccessToken());
			HttpRequest createReq = HttpRequest.newBuilder()
					.uri(URI.create(createUrl))
					.timeout(Duration.ofSeconds(60))
					.POST(HttpRequest.BodyPublishers.noBody())
					.build();
			HttpResponse<String> createResp = http.send(createReq, HttpResponse.BodyHandlers.ofString());
			if (createResp.statusCode() < 200 || createResp.statusCode() >= 300) {
				log.warn("IG container HTTP {}: {}", createResp.statusCode(), truncate(createResp.body(), 300));
				throw new BusinessRuleException("Instagram Reels container failed (HTTP "
						+ createResp.statusCode() + ").");
			}
			String containerId = text(objectMapper.readTree(createResp.body()), "id");
			if (containerId == null) {
				throw new BusinessRuleException("Instagram did not return a container id.");
			}
			waitIgContainerReady(containerId, tokens.pageAccessToken(), v);

			String publishUrl = "https://graph.facebook.com/" + v + "/" + tokens.igUserId() + "/media_publish"
					+ "?creation_id=" + enc(containerId)
					+ "&access_token=" + enc(tokens.pageAccessToken());
			HttpRequest pubReq = HttpRequest.newBuilder()
					.uri(URI.create(publishUrl))
					.timeout(Duration.ofSeconds(60))
					.POST(HttpRequest.BodyPublishers.noBody())
					.build();
			HttpResponse<String> pubResp = http.send(pubReq, HttpResponse.BodyHandlers.ofString());
			if (pubResp.statusCode() < 200 || pubResp.statusCode() >= 300) {
				throw new BusinessRuleException("Instagram publish failed (HTTP " + pubResp.statusCode() + ").");
			}
			String mediaId = text(objectMapper.readTree(pubResp.body()), "id");
			return mediaId != null ? mediaId : containerId;
		}
		catch (BusinessRuleException ex) {
			throw ex;
		}
		catch (Exception ex) {
			log.warn("Instagram publish failed: {}", ex.getMessage());
			throw new BusinessRuleException("Instagram publish failed: " + ex.getMessage());
		}
	}

	private void waitIgContainerReady(String containerId, String token, String version) throws Exception {
		long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
		while (System.nanoTime() < deadline) {
			String url = "https://graph.facebook.com/" + version + "/" + containerId
					+ "?fields=status_code"
					+ "&access_token=" + enc(token);
			HttpResponse<String> resp = http.send(
					HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			JsonNode root = objectMapper.readTree(resp.body());
			String status = text(root, "status_code");
			if ("FINISHED".equalsIgnoreCase(status)) {
				return;
			}
			if ("ERROR".equalsIgnoreCase(status) || "EXPIRED".equalsIgnoreCase(status)) {
				throw new BusinessRuleException("Instagram container status: " + status);
			}
			Thread.sleep(3000L);
		}
		throw new BusinessRuleException("Instagram container timed out.");
	}

	private byte[] downloadBytes(String videoUrl) throws Exception {
		HttpResponse<InputStream> media = http.send(
				HttpRequest.newBuilder().uri(URI.create(videoUrl)).timeout(Duration.ofMinutes(2)).GET().build(),
				HttpResponse.BodyHandlers.ofInputStream());
		if (media.statusCode() < 200 || media.statusCode() >= 300) {
			throw new BusinessRuleException("Could not download promo video for upload.");
		}
		try (InputStream in = media.body(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			in.transferTo(out);
			return out.toByteArray();
		}
	}

	private String ensureYoutubeAccess(Tokens tokens) throws Exception {
		if (tokens.expiresAt() == null || tokens.expiresAt().isAfter(Instant.now().plusSeconds(120))) {
			return tokens.accessToken();
		}
		if (tokens.refreshToken() == null) {
			throw new BusinessRuleException("YouTube authorization expired. Reconnect in Connections.");
		}
		GoogleTokenResponse response = new GoogleRefreshTokenRequest(
				new NetHttpTransport(),
				GsonFactory.getDefaultInstance(),
				tokens.refreshToken(),
				properties.getYoutubeClientId(),
				properties.getYoutubeClientSecret()).execute();
		return response.getAccessToken();
	}

	private static String enc(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
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
}
