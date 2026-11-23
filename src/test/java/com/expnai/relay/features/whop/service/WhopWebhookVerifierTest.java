package com.expnai.relay.features.whop.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

class WhopWebhookVerifierTest {

	private static final String SECRET = "ws_0123456789abcdef0123456789abcdef";
	private static final String ID = "msg_abc";
	private static final String BODY = "{\"type\":\"payment.succeeded\"}";

	private final WhopWebhookVerifier verifier = new WhopWebhookVerifier();
	private final Instant now = Instant.parse("2026-11-22T12:00:00Z");

	private static String sign(String secret, String id, String ts, String body) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return Base64.getEncoder().encodeToString(
				mac.doFinal((id + "." + ts + "." + body).getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void acceptsValidSignature() throws Exception {
		String ts = String.valueOf(now.getEpochSecond());
		assertTrue(verifier.verify(SECRET, ID, ts, "v1," + sign(SECRET, ID, ts, BODY), BODY, now));
	}

	@Test
	void acceptsWhenAnyListedSignatureMatches() throws Exception {
		String ts = String.valueOf(now.getEpochSecond());
		String header = "v1,AAAA v1," + sign(SECRET, ID, ts, BODY);
		assertTrue(verifier.verify(SECRET, ID, ts, header, BODY, now));
	}

	@Test
	void rejectsTamperedBodyWrongSecretAndMissingParts() throws Exception {
		String ts = String.valueOf(now.getEpochSecond());
		String header = "v1," + sign(SECRET, ID, ts, BODY);
		assertFalse(verifier.verify(SECRET, ID, ts, header, BODY + " ", now));
		assertFalse(verifier.verify("ws_other", ID, ts, header, BODY, now));
		assertFalse(verifier.verify(SECRET, ID, ts, null, BODY, now));
		assertFalse(verifier.verify(null, ID, ts, header, BODY, now));
		assertFalse(verifier.verify(SECRET, ID, "not-a-number", header, BODY, now));
	}

	@Test
	void rejectsStaleTimestamp() throws Exception {
		String ts = String.valueOf(now.minusSeconds(6 * 60).getEpochSecond());
		assertFalse(verifier.verify(SECRET, ID, ts, "v1," + sign(SECRET, ID, ts, BODY), BODY, now));
	}
}
