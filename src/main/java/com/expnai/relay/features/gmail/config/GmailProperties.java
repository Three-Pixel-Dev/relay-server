package com.expnai.relay.features.gmail.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "relay.gmail")
public class GmailProperties {

	/** OAuth web client id for Gmail mailbox connect (not Sign-In). */
	private String oauthClientId = "";

	private String oauthClientSecret = "";

	private String oauthRedirectUri = "http://localhost:8080/api/gmail/oauth/callback";

	/** Frontend base URL for OAuth browser redirect after callback. */
	private String frontendRedirectBase = "http://localhost:5173";

	/** When false, History sync and follow-up schedulers are not registered. */
	private boolean schedulingEnabled = true;

	/** Default sync interval in milliseconds (scheduler fixedDelay). */
	private long syncIntervalMs = 60_000L;

	/** Follow-up send poll interval in milliseconds. */
	private long followupIntervalMs = 60_000L;

	/** Sync lease duration so another instance can take over if a worker dies. */
	private long syncLeaseSeconds = 90L;

	/** Max History API pages per sync tick. */
	private int historyMaxPages = 20;

	/** Initial backoff for rate limits (ms). */
	private long rateLimitInitialBackoffMs = 1_000L;

	/** Max backoff for rate limits (ms). */
	private long rateLimitMaxBackoffMs = 30_000L;

	private int rateLimitMaxAttempts = 5;

	/** Instance id used as sync lease owner (defaults to hostname in service). */
	private String instanceId = "";
}
