package com.expnai.relay.features.promote.higgsfield;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.expnai.relay.common.exception.BusinessRuleException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * Minimal Higgsfield REST client: submit Wan 3.0 text-to-video, poll until terminal.
 * Auth: {@code Authorization: Key KEY_ID:KEY_SECRET}.
 */
@Component
@RequiredArgsConstructor
public class HiggsfieldClient {

	private static final Logger log = LoggerFactory.getLogger(HiggsfieldClient.class);

	public static final String TEXT_TO_VIDEO_URL = "https://api.higgsfield.ai/alibaba/wan-3.0/text-to-video";
	public static final String STATUS_URL_PREFIX = "https://api.higgsfield.ai/requests/";

	/** Wan 3.0 list floor (~480p) USD per output second — used for spend estimates. */
	public static final java.math.BigDecimal USD_PER_SECOND_480P = new java.math.BigDecimal("0.038");
	public static final int DEFAULT_DURATION_SECONDS = 20;
	public static final String DEFAULT_RESOLUTION = "480p";
	public static final String DEFAULT_ASPECT_RATIO = "9:16";

	private final ObjectMapper objectMapper;

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(15))
			.build();

	public record Credentials(String keyId, String keySecret) {
		public String authorizationHeader() {
			return "Key " + keyId + ":" + keySecret;
		}
	}

	public record SubmitResult(String requestId, String statusUrl) {
	}

	public record StatusResult(String status, String videoUrl, String error) {
		public boolean terminal() {
			return "completed".equalsIgnoreCase(status)
					|| "failed".equalsIgnoreCase(status)
					|| "nsfw".equalsIgnoreCase(status)
					|| "canceled".equalsIgnoreCase(status);
		}

		public boolean completed() {
			return "completed".equalsIgnoreCase(status) && videoUrl != null && !videoUrl.isBlank();
		}
	}

	/** Parse stored credential: {@code keyId:keySecret} (or legacy {@code KEY_ID/KEY_SECRET}). */
	public static Credentials parseCredentials(String raw) {
		String trimmed = raw != null ? raw.trim() : "";
		if (trimmed.isBlank()) {
			throw new BusinessRuleException("Higgsfield key is empty.");
		}
		String separator = trimmed.contains(":") ? ":" : (trimmed.contains("/") ? "/" : null);
		if (separator == null) {
			throw new BusinessRuleException(
					"Higgsfield credential must be KEY_ID:KEY_SECRET from the Higgsfield Console.");
		}
		int idx = trimmed.indexOf(separator);
		String keyId = trimmed.substring(0, idx).trim();
		String keySecret = trimmed.substring(idx + 1).trim();
		if (keyId.isBlank() || keySecret.isBlank()) {
			throw new BusinessRuleException(
					"Higgsfield credential must be KEY_ID:KEY_SECRET from the Higgsfield Console.");
		}
		return new Credentials(keyId, keySecret);
	}

	/** Lightweight auth probe before saving a connection. */
	public void probeAuth(Credentials credentials) {
		try {
			HttpRequest req = HttpRequest.newBuilder()
					.uri(URI.create(STATUS_URL_PREFIX + "00000000-0000-0000-0000-000000000000/status"))
					.timeout(Duration.ofSeconds(12))
					.header("Authorization", credentials.authorizationHeader())
					.header("Accept", "application/json")
					.GET()
					.build();
			HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
			// 401 = bad credentials; 404 = auth OK but request missing (expected for probe UUID).
			if (resp.statusCode() == 401 || resp.statusCode() == 403) {
				throw new BusinessRuleException("This key is not valid");
			}
			if (resp.statusCode() >= 500) {
				log.warn("Higgsfield probe returned HTTP {}", resp.statusCode());
				throw new BusinessRuleException("This key is not valid");
			}
		}
		catch (BusinessRuleException ex) {
			throw ex;
		}
		catch (Exception ex) {
			log.warn("Higgsfield auth probe failed: {}", ex.getMessage());
			throw new BusinessRuleException("This key is not valid");
		}
	}

	public SubmitResult submitTextToVideo(Credentials credentials, String prompt, int durationSeconds) {
		try {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("prompt", prompt);
			body.put("duration", durationSeconds);
			body.put("resolution", DEFAULT_RESOLUTION);
			body.put("aspect_ratio", DEFAULT_ASPECT_RATIO);
			body.put("generate_audio", true);

			String json = objectMapper.writeValueAsString(body);
			HttpRequest req = HttpRequest.newBuilder()
					.uri(URI.create(TEXT_TO_VIDEO_URL))
					.timeout(Duration.ofSeconds(60))
					.header("Authorization", credentials.authorizationHeader())
					.header("Content-Type", "application/json")
					.header("Accept", "application/json")
					.header("Idempotency-Key", UUID.randomUUID().toString())
					.POST(HttpRequest.BodyPublishers.ofString(json))
					.build();
			HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
				log.warn("Higgsfield submit rejected HTTP {}: {}", resp.statusCode(), truncate(resp.body()));
				throw new BusinessRuleException(mapSubmitError(resp.statusCode(), resp.body()));
			}
			JsonNode root = objectMapper.readTree(resp.body());
			String requestId = text(root, "request_id");
			String statusUrl = text(root, "status_url");
			if (requestId == null || requestId.isBlank()) {
				throw new BusinessRuleException("Higgsfield did not return a request id.");
			}
			if (statusUrl == null || statusUrl.isBlank()) {
				statusUrl = STATUS_URL_PREFIX + requestId + "/status";
			}
			return new SubmitResult(requestId, statusUrl);
		}
		catch (BusinessRuleException ex) {
			throw ex;
		}
		catch (Exception ex) {
			log.warn("Higgsfield submit failed: {}", ex.getMessage());
			throw new BusinessRuleException("Could not start Higgsfield video generation. Try again.");
		}
	}

	public StatusResult getStatus(Credentials credentials, String statusUrl) {
		try {
			HttpRequest req = HttpRequest.newBuilder()
					.uri(URI.create(statusUrl))
					.timeout(Duration.ofSeconds(30))
					.header("Authorization", credentials.authorizationHeader())
					.header("Accept", "application/json")
					.GET()
					.build();
			HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
				log.warn("Higgsfield status HTTP {}: {}", resp.statusCode(), truncate(resp.body()));
				return new StatusResult("failed", null, "Status check failed (HTTP " + resp.statusCode() + ")");
			}
			JsonNode root = objectMapper.readTree(resp.body());
			String status = text(root, "status");
			String videoUrl = null;
			JsonNode video = root.get("video");
			if (video != null && !video.isNull()) {
				videoUrl = text(video, "url");
			}
			String error = text(root, "error");
			if (error == null && root.get("detail") != null) {
				error = root.get("detail").asText(null);
			}
			return new StatusResult(status != null ? status : "unknown", videoUrl, error);
		}
		catch (Exception ex) {
			log.warn("Higgsfield status poll failed: {}", ex.getMessage());
			return new StatusResult("failed", null, ex.getMessage());
		}
	}

	/**
	 * Poll until terminal or timeout. Returns completed result or throws.
	 */
	public StatusResult waitForCompletion(Credentials credentials, String statusUrl, Duration timeout) {
		long deadline = System.nanoTime() + timeout.toNanos();
		long sleepMs = 2000L;
		StatusResult last = null;
		while (System.nanoTime() < deadline) {
			last = getStatus(credentials, statusUrl);
			if (last.terminal()) {
				if (last.completed()) {
					return last;
				}
				String reason = last.error() != null ? last.error() : last.status();
				throw new BusinessRuleException("Higgsfield generation ended: " + reason);
			}
			try {
				Thread.sleep(sleepMs);
			}
			catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				throw new BusinessRuleException("Higgsfield generation interrupted.");
			}
			sleepMs = Math.min(10_000L, (long) (sleepMs * 1.4));
		}
		throw new BusinessRuleException(
				"Higgsfield generation timed out"
						+ (last != null ? " (last status: " + last.status() + ")" : "")
						+ ".");
	}

	private static String mapSubmitError(int status, String body) {
		if (status == 401 || status == 403) {
			return "Higgsfield rejected the API key. Re-save it in Connections.";
		}
		if (status == 402 || (body != null && body.toLowerCase().contains("balance"))) {
			return "Higgsfield account has insufficient balance.";
		}
		return "Higgsfield could not start the video (HTTP " + status + ").";
	}

	private static String text(JsonNode node, String field) {
		if (node == null || field == null) {
			return null;
		}
		JsonNode child = node.get(field);
		if (child == null || child.isNull()) {
			return null;
		}
		String value = child.asText(null);
		return value != null && !value.isBlank() ? value : null;
	}

	private static String truncate(String body) {
		if (body == null) {
			return "";
		}
		return body.length() > 400 ? body.substring(0, 400) + "…" : body;
	}
}
