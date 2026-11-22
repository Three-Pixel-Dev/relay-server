package com.expnai.relay.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;

import org.junit.jupiter.api.Test;

class TokenEncryptionServiceTest {

	private static String key32() {
		byte[] raw = new byte[32];
		for (int i = 0; i < raw.length; i++) {
			raw[i] = (byte) (i + 1);
		}
		return Base64.getEncoder().encodeToString(raw);
	}

	private static String otherKey32() {
		byte[] raw = new byte[32];
		for (int i = 0; i < raw.length; i++) {
			raw[i] = (byte) (100 - i);
		}
		return Base64.getEncoder().encodeToString(raw);
	}

	@Test
	void encryptDecryptRoundTrip() {
		TokenEncryptionService svc = new TokenEncryptionService(key32(), 1, "", 0);
		String cipher = svc.encrypt("refresh-token-secret");
		assertTrue(cipher.startsWith("enc:v1:"));
		assertEquals("refresh-token-secret", svc.decrypt(cipher));
	}

	@Test
	void uniqueIvEachEncrypt() {
		TokenEncryptionService svc = new TokenEncryptionService(key32(), 1, "", 0);
		String a = svc.encrypt("same-plaintext");
		String b = svc.encrypt("same-plaintext");
		assertNotEquals(a, b);
	}

	@Test
	void rejectsNon32ByteKey() {
		String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
		assertThrows(IllegalStateException.class, () -> new TokenEncryptionService(shortKey, 1, "", 0));
	}

	@Test
	void decryptFailsWithWrongKey() {
		TokenEncryptionService encryptor = new TokenEncryptionService(key32(), 1, "", 0);
		String cipher = encryptor.encrypt("secret");
		TokenEncryptionService wrong = new TokenEncryptionService(otherKey32(), 1, "", 0);
		assertThrows(IllegalStateException.class, () -> wrong.decrypt(cipher));
	}

	@Test
	void decryptWithPreviousKeyDuringRotation() {
		TokenEncryptionService oldSvc = new TokenEncryptionService(key32(), 1, "", 0);
		String cipher = oldSvc.encrypt("legacy-refresh");
		TokenEncryptionService rotated = new TokenEncryptionService(otherKey32(), 2, key32(), 1);
		assertEquals("legacy-refresh", rotated.decrypt(cipher));
		String fresh = rotated.encrypt("new-refresh");
		assertTrue(fresh.startsWith("enc:v2:"));
		assertEquals("new-refresh", rotated.decrypt(fresh));
	}

	@Test
	void notConfiguredWithoutKey() {
		TokenEncryptionService svc = new TokenEncryptionService("", 1, "", 0);
		assertFalse(svc.isConfigured());
		assertThrows(IllegalStateException.class, () -> svc.encrypt("x"));
	}
}
