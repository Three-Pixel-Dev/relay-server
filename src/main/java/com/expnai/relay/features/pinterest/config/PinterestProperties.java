package com.expnai.relay.features.pinterest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "relay.pinterest")
public class PinterestProperties {

	/** App ID from https://developers.pinterest.com/apps/ */
	private String oauthClientId = "";

	private String oauthClientSecret = "";

	private String oauthRedirectUri = "http://localhost:8080/api/pinterest/oauth/callback";

	private String frontendRedirectBase = "http://localhost:5173";

	private String apiBaseUrl = "https://api.pinterest.com/v5";

	/** Paid channel: $5/day in microcurrency (USD × 1_000_000). */
	private long paidDailyBudgetMicros = 5_000_000L;

	/** Paid channel hard stop: $50 lifetime. */
	private long paidLifetimeBudgetMicros = 50_000_000L;
}
