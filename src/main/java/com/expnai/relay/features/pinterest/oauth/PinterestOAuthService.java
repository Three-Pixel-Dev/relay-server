package com.expnai.relay.features.pinterest.oauth;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.expnai.relay.common.exception.BusinessRuleException;
import com.expnai.relay.features.pinterest.config.PinterestProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PinterestOAuthService {

	private static final Logger log = LoggerFactory.getLogger(PinterestOAuthService.class);

	public static final List<String> SCOPES = List.of(
			"boards:read",
			"boards:write",
			"pins:read",
			"pins:write",
			"user_accounts:read",
			"ads:read",
			"ads:write");

	private final PinterestProperties properties;
	private final ObjectMapper objectMapper;

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(15))
			.build();

	public boolean isConfigured() {
		return StringUtils.hasText(properties.getOauthClientId())
				&& StringUtils.hasText(properties.getOauthClientSecret())
				&& StringUtils.hasText(properties.getOauthRedirectUri());
	}

	public String buildAuthorizationUrl(String state) {
		requireConfigured();
		String scope = SCOPES.stream().collect(Collectors.joining(","));
		return "https://www.pinterest.com/oauth/"
				+ "?client_id=" + enc(properties.getOauthClientId())
				+ "&redirect_uri=" + enc(properties.getOauthRedirectUri())
				+ "&response_type=code"
				+ "&scope=" + enc(scope)
				+ "&state=" + enc(state);
	}

	public TokenBundle exchangeCode(String code) {
		requireConfigured();
		try {
			String body = "grant_type=authorization_code"
					+ "&code=" + enc(code)
					+ "&redirect_uri=" + enc(properties.getOauthRedirectUri());
			return postToken(body);
		}
		catch (BusinessRuleException ex) {
			throw ex;
		}
		catch (Exception ex) {
			log.warn("Pinterest OAuth code exchange failed: {}", ex.getMessage());
			throw new BusinessRuleException("Pinterest authorization failed. Please try connecting again.");
		}
	}

	public TokenBundle refresh(String refreshToken) {
		requireConfigured();
		try {
			String body = "grant_type=refresh_token"
					+ "&refresh_token=" + enc(refreshToken);
			return postToken(body);
		}
		catch (BusinessRuleException ex) {
			throw ex;
		}
		catch (Exception ex) {
			log.warn("Pinterest OAuth refresh failed: {}", ex.getMessage());
			throw new BusinessRuleException("Pinterest authorization expired. Reconnect in Connections.");
		}
	}

	private TokenBundle postToken(String formBody) throws Exception {
		String basic = Base64.getEncoder().encodeToString(
				(properties.getOauthClientId() + ":" + properties.getOauthClientSecret())
						.getBytes(StandardCharsets.UTF_8));
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(properties.getApiBaseUrl() + "/oauth/token"))
				.timeout(Duration.ofSeconds(30))
				.header("Authorization", "Basic " + basic)
				.header("Content-Type", "application/x-www-form-urlencoded")
				.header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(formBody))
				.build();
		HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
		if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
			log.warn("Pinterest token HTTP {}: {}", resp.statusCode(), truncate(resp.body()));
			throw new BusinessRuleException("Pinterest authorization failed. Please try connecting again.");
		}
		JsonNode root = objectMapper.readTree(resp.body());
		String access = text(root, "access_token");
		String refresh = text(root, "refresh_token");
		if (!StringUtils.hasText(access)) {
			throw new BusinessRuleException("Pinterest did not return an access token.");
		}
		long expiresIn = root.path("expires_in").asLong(2592000L);
		String scope = text(root, "scope");
		return new TokenBundle(
				access,
				refresh,
				Instant.now().plusSeconds(Math.max(60, expiresIn - 60)),
				scope);
	}

	private void requireConfigured() {
		if (!isConfigured()) {
			throw new BusinessRuleException(
					"Pinterest OAuth is not configured. Set PINTEREST_APP_ID, PINTEREST_APP_SECRET, PINTEREST_OAUTH_REDIRECT_URI.");
		}
	}

	private static String enc(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static String text(JsonNode node, String field) {
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

	public record TokenBundle(
			String accessToken,
			String refreshToken,
			Instant accessTokenExpiresAt,
			String scope
	) {
	}
}
