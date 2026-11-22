package com.expnai.relay.features.gmail.oauth;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.expnai.relay.common.exception.BusinessRuleException;
import com.expnai.relay.features.gmail.config.GmailProperties;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleRefreshTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class GmailOAuthService {

	private static final Logger log = LoggerFactory.getLogger(GmailOAuthService.class);

	public static final Collection<String> SCOPES = List.of(
			"https://www.googleapis.com/auth/gmail.send",
			"https://www.googleapis.com/auth/gmail.readonly",
			"https://www.googleapis.com/auth/userinfo.email",
			"openid");

	private final GmailProperties properties;

	public boolean isConfigured() {
		return StringUtils.hasText(properties.getOauthClientId())
				&& StringUtils.hasText(properties.getOauthClientSecret())
				&& StringUtils.hasText(properties.getOauthRedirectUri());
	}

	public String buildAuthorizationUrl(String state) {
		requireConfigured();
		String scope = String.join(" ", SCOPES);
		return "https://accounts.google.com/o/oauth2/v2/auth"
				+ "?client_id=" + enc(properties.getOauthClientId())
				+ "&redirect_uri=" + enc(properties.getOauthRedirectUri())
				+ "&response_type=code"
				+ "&scope=" + enc(scope)
				+ "&access_type=offline"
				+ "&prompt=consent"
				+ "&include_granted_scopes=true"
				+ "&state=" + enc(state);
	}

	public TokenBundle exchangeCode(String code) {
		requireConfigured();
		try {
			GoogleTokenResponse response = new GoogleAuthorizationCodeTokenRequest(
					new NetHttpTransport(),
					GsonFactory.getDefaultInstance(),
					"https://oauth2.googleapis.com/token",
					properties.getOauthClientId(),
					properties.getOauthClientSecret(),
					code,
					properties.getOauthRedirectUri()).execute();
			return toBundle(response, null);
		}
		catch (IOException ex) {
			log.warn("Gmail OAuth code exchange failed: {}", ex.getClass().getSimpleName());
			throw new BusinessRuleException("Gmail authorization failed. Please try connecting again.");
		}
	}

	/**
	 * Refresh access token. Pass existing refresh token; Google may omit a new refresh token — preserve the old one.
	 */
	public TokenBundle refresh(String refreshToken) {
		requireConfigured();
		try {
			GoogleTokenResponse response = new GoogleRefreshTokenRequest(
					new NetHttpTransport(),
					GsonFactory.getDefaultInstance(),
					refreshToken,
					properties.getOauthClientId(),
					properties.getOauthClientSecret()).execute();
			return toBundle(response, refreshToken);
		}
		catch (IOException ex) {
			log.warn("Gmail OAuth refresh failed: {}", ex.getClass().getSimpleName());
			throw new GmailReauthRequiredException("Gmail authorization expired. Reconnect the mailbox.");
		}
	}

	private TokenBundle toBundle(GoogleTokenResponse response, String existingRefresh) {
		String refresh = StringUtils.hasText(response.getRefreshToken())
				? response.getRefreshToken()
				: existingRefresh;
		if (!StringUtils.hasText(refresh)) {
			throw new BusinessRuleException("Google did not return a refresh token. Reconnect with consent.");
		}
		long expiresIn = response.getExpiresInSeconds() != null ? response.getExpiresInSeconds() : 3500L;
		return new TokenBundle(
				response.getAccessToken(),
				refresh,
				Instant.now().plusSeconds(Math.max(60, expiresIn - 60)),
				response.getScope());
	}

	private void requireConfigured() {
		if (!isConfigured()) {
			throw new BusinessRuleException(
					"Gmail OAuth is not configured. Set GMAIL_OAUTH_CLIENT_ID, GMAIL_OAUTH_CLIENT_SECRET, GMAIL_OAUTH_REDIRECT_URI.");
		}
	}

	private static String enc(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	public record TokenBundle(
			String accessToken,
			String refreshToken,
			Instant accessTokenExpiresAt,
			String scope
	) {
	}
}
