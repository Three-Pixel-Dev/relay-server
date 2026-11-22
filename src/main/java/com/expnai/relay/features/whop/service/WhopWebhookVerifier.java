package com.expnai.relay.features.whop.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * Verifies Whop webhooks (Standard Webhooks): HMAC-SHA256 over {@code id.timestamp.body}, keyed with the
 * raw UTF-8 bytes of the {@code ws_...} secret, compared against every {@code v1,<base64>} entry in the
 * {@code webhook-signature} header. Timestamps more than five minutes off are rejected to stop replays.
 */
@Component
public class WhopWebhookVerifier {

	static final Duration TOLERANCE = Duration.ofMinutes(5);

	public boolean verify(String secret, String webhookId, String timestamp, String signatureHeader, String body,
			Instant now) {
		if (isBlank(secret) || isBlank(webhookId) || isBlank(timestamp) || isBlank(signatureHeader) || body == null) {
			return false;
		}
		long seconds;
		try {
			seconds = Long.parseLong(timestamp.trim());
		}
		catch (NumberFormatException ex) {
			return false;
		}
		if (Math.abs(Duration.between(Instant.ofEpochSecond(seconds), now).toSeconds()) > TOLERANCE.toSeconds()) {
			return false;
		}
		byte[] expected;
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			expected = mac.doFinal((webhookId + "." + timestamp.trim() + "." + body).getBytes(StandardCharsets.UTF_8));
		}
		catch (GeneralSecurityException ex) {
			return false;
		}
		for (String part : signatureHeader.trim().split("\\s+")) {
			int comma = part.indexOf(',');
			if (comma < 0 || !"v1".equals(part.substring(0, comma))) {
				continue;
			}
			try {
				byte[] provided = Base64.getDecoder().decode(part.substring(comma + 1));
				if (MessageDigest.isEqual(expected, provided)) {
					return true;
				}
			}
			catch (IllegalArgumentException ignored) {
				// malformed entry, try the next one
			}
		}
		return false;
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
