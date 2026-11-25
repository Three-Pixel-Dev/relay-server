package com.expnai.relay.features.reach;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.expnai.relay.common.exception.BusinessRuleException;
import com.expnai.relay.features.reach.ReachTokenStore.Tokens;
import com.expnai.relay.features.reach.config.ReachProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ReachOAuthService {

	private static final Logger log = LoggerFactory.getLogger(ReachOAuthService.class);

	public static final List<String> YOUTUBE_SCOPES = List.of(
			"https://www.googleapis.com/auth/youtube.upload",
			"https://www.googleapis.com/auth/youtube.readonly",
			"https://www.googleapis.com/auth/userinfo.email",
			"openid");

	public static final List<String> TIKTOK_SCOPES = List.of(
			"user.info.basic",
			"video.upload",
			"video.publish");

	public static final List<String> INSTAGRAM_SCOPES = List.of(
			"instagram_basic",
			"instagram_content_publish",
			"pages_show_list",
			"pages_read_engagement",
			"business_management");

	private final ReachProperties properties;
	private final ObjectMapper objectMapper;

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(15))
			.build();

	public String buildAuthorizationUrl(String serviceId, String state) {
		return switch (serviceId.toLowerCase(Locale.ROOT)) {
			case "youtube" -> buildYoutubeUrl(state);
			case "tiktok" -> buildTiktokUrl(state);
			case "instagram" -> buildInstagramUrl(state);
			default -> throw new BusinessRuleException("Unknown reach service.");
		};
	}

	public Tokens exchangeCode(String serviceId, String code) {
		return switch (serviceId.toLowerCase(Locale.ROOT)) {
			case "youtube" -> exchangeYoutube(code);
			case "tiktok" -> exchangeTiktok(code);
			case "instagram" -> exchangeInstagram(code);
			default -> throw new BusinessRuleException("Unknown reach service.");
		};
	}

	public boolean isConfigured(String serviceId) {
		return switch (serviceId.toLowerCase(Locale.ROOT)) {
			case "youtube" -> properties.youtubeConfigured();
			case "tiktok" -> properties.tiktokConfigured();
			case "instagram" -> properties.instagramConfigured();
			default -> false;
		};
	}

	public String configHint(String serviceId) {
		return switch (serviceId.toLowerCase(Locale.ROOT)) {
			case "youtube" -> "Set YOUTUBE_OAUTH_CLIENT_ID / SECRET / REDIRECT_URI (Google Cloud OAuth client with YouTube Data API).";
			case "tiktok" -> "Set TIKTOK_CLIENT_KEY / SECRET / REDIRECT_URI (TikTok for Developers Content Posting API).";
			case "instagram" -> "Set INSTAGRAM_APP_ID / SECRET / REDIRECT_URI (Meta app with Instagram content publish).";
			default -> "Reach OAuth is not configured.";
		};
	}

	private String buildYoutubeUrl(String state) {
		require("youtube");
		String scope = YOUTUBE_SCOPES.stream().collect(Collectors.joining(" "));
		return "https://accounts.google.com/o/oauth2/v2/auth"
				+ "?client_id=" + enc(properties.getYoutubeClientId())
				+ "&redirect_uri=" + enc(properties.getYoutubeRedirectUri())
				+ "&response_type=code"
				+ "&scope=" + enc(scope)
				+ "&access_type=offline"
				+ "&prompt=consent"
				+ "&include_granted_scopes=true"
				+ "&state=" + enc(state);
	}

	private String buildTiktokUrl(String state) {
		require("tiktok");
		String scope = String.join(",", TIKTOK_SCOPES);
		return "https://www.tiktok.com/v2/auth/authorize/"
				+ "?client_key=" + enc(properties.getTiktokClientKey())
				+ "&redirect_uri=" + enc(properties.getTiktokRedirectUri())
				+ "&response_type=code"
				+ "&scope=" + enc(scope)
				+ "&state=" + enc(state);
	}

	private String buildInstagramUrl(String state) {
		require("instagram");
		String scope = String.join(",", INSTAGRAM_SCOPES);
		return "https://www.facebook.com/" + properties.getInstagramGraphVersion() + "/dialog/oauth"
				+ "?client_id=" + enc(properties.getInstagramAppId())
				+ "&redirect_uri=" + enc(properties.getInstagramRedirectUri())
				+ "&response_type=code"
				+ "&scope=" + enc(scope)
				+ "&state=" + enc(state);
	}

	private Tokens exchangeYoutube(String code) {
		require("youtube");
		try {
			GoogleTokenResponse response = new GoogleAuthorizationCodeTokenRequest(
					new NetHttpTransport(),
					GsonFactory.getDefaultInstance(),
					"https://oauth2.googleapis.com/token",
					properties.getYoutubeClientId(),
					properties.getYoutubeClientSecret(),
					code,
					properties.getYoutubeRedirectUri()).execute();
			String refresh = response.getRefreshToken();
			long expiresIn = response.getExpiresInSeconds() != null ? response.getExpiresInSeconds() : 3500L;
			return new Tokens(
					response.getAccessToken(),
					refresh,
					Instant.now().plusSeconds(Math.max(60, expiresIn - 60)),
					null, null, null);
		}
		catch (IOException ex) {
			log.warn("YouTube OAuth exchange failed: {}", ex.getClass().getSimpleName());
			throw new BusinessRuleException("YouTube authorization failed. Try connecting again.");
		}
	}

	private Tokens exchangeTiktok(String code) {
		require("tiktok");
		try {
			String body = "client_key=" + enc(properties.getTiktokClientKey())
					+ "&client_secret=" + enc(properties.getTiktokClientSecret())
					+ "&code=" + enc(code)
					+ "&grant_type=authorization_code"
					+ "&redirect_uri=" + enc(properties.getTiktokRedirectUri());
			HttpRequest req = HttpRequest.newBuilder()
					.uri(URI.create("https://open.tiktokapis.com/v2/oauth/token/"))
					.timeout(Duration.ofSeconds(30))
					.header("Content-Type", "application/x-www-form-urlencoded")
					.header("Accept", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(body))
					.build();
			HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
				log.warn("TikTok token HTTP {}: {}", resp.statusCode(), truncate(resp.body()));
				throw new BusinessRuleException("TikTok authorization failed. Try connecting again.");
			}
			JsonNode root = objectMapper.readTree(resp.body());
			JsonNode data = root.has("data") ? root.get("data") : root;
			String access = text(data, "access_token");
			String refresh = text(data, "refresh_token");
			String openId = text(data, "open_id");
			long expiresIn = data.path("expires_in").asLong(86400L);
			if (!StringUtils.hasText(access)) {
				throw new BusinessRuleException("TikTok did not return an access token.");
			}
			return new Tokens(access, refresh, Instant.now().plusSeconds(Math.max(60, expiresIn - 60)),
					openId, null, null);
		}
		catch (BusinessRuleException ex) {
			throw ex;
		}
		catch (Exception ex) {
			log.warn("TikTok OAuth exchange failed: {}", ex.getMessage());
			throw new BusinessRuleException("TikTok authorization failed. Try connecting again.");
		}
	}

	private Tokens exchangeInstagram(String code) {
		require("instagram");
		try {
			String body = "client_id=" + enc(properties.getInstagramAppId())
					+ "&client_secret=" + enc(properties.getInstagramAppSecret())
					+ "&redirect_uri=" + enc(properties.getInstagramRedirectUri())
					+ "&code=" + enc(code);
			HttpRequest req = HttpRequest.newBuilder()
					.uri(URI.create("https://graph.facebook.com/" + properties.getInstagramGraphVersion()
							+ "/oauth/access_token"))
					.timeout(Duration.ofSeconds(30))
					.header("Content-Type", "application/x-www-form-urlencoded")
					.POST(HttpRequest.BodyPublishers.ofString(body))
					.build();
			HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
				log.warn("Instagram token HTTP {}: {}", resp.statusCode(), truncate(resp.body()));
				throw new BusinessRuleException("Instagram authorization failed. Try connecting again.");
			}
			JsonNode root = objectMapper.readTree(resp.body());
			String shortLived = text(root, "access_token");
			Tokens longLived = exchangeInstagramLongLived(shortLived);
			InstagramTargets targets = resolveInstagramTargets(longLived.accessToken());
			return new Tokens(
					longLived.accessToken(),
					longLived.refreshToken(),
					longLived.expiresAt(),
					null,
					targets.pageAccessToken(),
					targets.igUserId());
		}
		catch (BusinessRuleException ex) {
			throw ex;
		}
		catch (Exception ex) {
			log.warn("Instagram OAuth exchange failed: {}", ex.getMessage());
			throw new BusinessRuleException("Instagram authorization failed. Try connecting again.");
		}
	}

	private Tokens exchangeInstagramLongLived(String shortLived) throws Exception {
		String url = "https://graph.facebook.com/" + properties.getInstagramGraphVersion()
				+ "/oauth/access_token"
				+ "?grant_type=fb_exchange_token"
				+ "&client_id=" + enc(properties.getInstagramAppId())
				+ "&client_secret=" + enc(properties.getInstagramAppSecret())
				+ "&fb_exchange_token=" + enc(shortLived);
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.timeout(Duration.ofSeconds(30))
				.GET()
				.build();
		HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
		JsonNode root = objectMapper.readTree(resp.body());
		String access = text(root, "access_token");
		if (!StringUtils.hasText(access)) {
			access = shortLived;
		}
		long expiresIn = root.path("expires_in").asLong(5_184_000L);
		return new Tokens(access, null, Instant.now().plusSeconds(Math.max(60, expiresIn - 60)),
				null, null, null);
	}

	private record InstagramTargets(String pageAccessToken, String igUserId) {
	}

	private InstagramTargets resolveInstagramTargets(String userToken) throws Exception {
		String pagesUrl = "https://graph.facebook.com/" + properties.getInstagramGraphVersion()
				+ "/me/accounts?fields=id,name,access_token,instagram_business_account"
				+ "&access_token=" + enc(userToken);
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(pagesUrl))
				.timeout(Duration.ofSeconds(30))
				.GET()
				.build();
		HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
		JsonNode root = objectMapper.readTree(resp.body());
		JsonNode data = root.get("data");
		if (data != null && data.isArray()) {
			for (JsonNode page : data) {
				JsonNode ig = page.get("instagram_business_account");
				if (ig != null && text(ig, "id") != null) {
					return new InstagramTargets(text(page, "access_token"), text(ig, "id"));
				}
			}
		}
		throw new BusinessRuleException(
				"No Instagram professional account linked to a Facebook Page was found. Link IG ↔ Page in Meta Business Suite.");
	}

	private void require(String serviceId) {
		if (!isConfigured(serviceId)) {
			throw new BusinessRuleException(configHint(serviceId));
		}
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
		return StringUtils.hasText(v) ? v : null;
	}

	private static String truncate(String body) {
		if (body == null) {
			return "";
		}
		return body.length() > 300 ? body.substring(0, 300) + "…" : body;
	}
}
