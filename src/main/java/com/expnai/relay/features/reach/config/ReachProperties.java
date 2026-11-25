package com.expnai.relay.features.reach.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "relay.reach")
public class ReachProperties {

	private String frontendRedirectBase = "http://localhost:5173";

	/** YouTube uses Google OAuth (can share Gmail app client). */
	private String youtubeClientId = "";
	private String youtubeClientSecret = "";
	private String youtubeRedirectUri = "http://localhost:8080/api/youtube/oauth/callback";

	private String tiktokClientKey = "";
	private String tiktokClientSecret = "";
	private String tiktokRedirectUri = "http://localhost:8080/api/tiktok/oauth/callback";

	private String instagramAppId = "";
	private String instagramAppSecret = "";
	private String instagramRedirectUri = "http://localhost:8080/api/instagram/oauth/callback";

	/** Graph API version for Instagram publishing. */
	private String instagramGraphVersion = "v22.0";

	public boolean youtubeConfigured() {
		return StringUtils.hasText(youtubeClientId) && StringUtils.hasText(youtubeClientSecret)
				&& StringUtils.hasText(youtubeRedirectUri);
	}

	public boolean tiktokConfigured() {
		return StringUtils.hasText(tiktokClientKey) && StringUtils.hasText(tiktokClientSecret)
				&& StringUtils.hasText(tiktokRedirectUri);
	}

	public boolean instagramConfigured() {
		return StringUtils.hasText(instagramAppId) && StringUtils.hasText(instagramAppSecret)
				&& StringUtils.hasText(instagramRedirectUri);
	}
}
