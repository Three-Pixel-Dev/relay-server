package com.expnai.relay.common.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * AES-256-GCM encryption for user service credentials ({@code relay_service_connection.encrypted_credential}).
 * <p>
 * Ciphertext format: {@code enc:v1:<base64(12-byte-iv || ciphertext+tag)>}.
 * Legacy unprefixed values in existing dev rows are returned as-is on decrypt so existing connections do not break.
 */
@Service
public class CredentialEncryptionService {

	private static final String ALGORITHM = "AES/GCM/NoPadding";
	private static final String PREFIX = "enc:v1:";
	private static final int IV_LENGTH_BYTES = 12;
	private static final int TAG_LENGTH_BITS = 128;

	private final SecretKeySpec secretKey;
	private final SecureRandom secureRandom = new SecureRandom();

	public CredentialEncryptionService(
			@Value("${relay.credential.encryption-secret:${jwt.secret:relay-dev-secret-change-me-please-32chars-min}}") String secret) {
		try {
			MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
			byte[] keyBytes = sha256.digest(secret.getBytes(StandardCharsets.UTF_8));
			this.secretKey = new SecretKeySpec(keyBytes, "AES");
		}
		catch (Exception ex) {
			throw new IllegalStateException("Failed to initialize AES-256 key for credential encryption", ex);
		}
	}

	public String encrypt(String plaintext) {
		if (plaintext == null || plaintext.isBlank()) {
			return null;
		}
		if (plaintext.startsWith(PREFIX)) {
			return plaintext;
		}
		try {
			byte[] iv = new byte[IV_LENGTH_BYTES];
			secureRandom.nextBytes(iv);

			Cipher cipher = Cipher.getInstance(ALGORITHM);
			cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
			byte[] cipherBytes = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

			ByteBuffer buf = ByteBuffer.allocate(iv.length + cipherBytes.length);
			buf.put(iv);
			buf.put(cipherBytes);
			return PREFIX + Base64.getEncoder().encodeToString(buf.array());
		}
		catch (Exception ex) {
			throw new IllegalStateException("Failed to encrypt service credential", ex);
		}
	}

	public String decrypt(String storedValue) {
		if (storedValue == null || storedValue.isBlank()) {
			return null;
		}
		String trimmed = storedValue.trim();
		if (!trimmed.startsWith(PREFIX)) {
			// Backward-compatible fallback for pre-encryption dev rows
			return trimmed;
		}
		try {
			String encoded = trimmed.substring(PREFIX.length());
			byte[] raw = Base64.getDecoder().decode(encoded);
			if (raw.length <= IV_LENGTH_BYTES) {
				throw new IllegalArgumentException("Invalid encrypted credential payload");
			}
			Cipher cipher = Cipher.getInstance(ALGORITHM);
			GCMParameterSpec spec = new GCMParameterSpec(TAG_LENGTH_BITS, raw, 0, IV_LENGTH_BYTES);
			cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
			byte[] plainBytes = cipher.doFinal(raw, IV_LENGTH_BYTES, raw.length - IV_LENGTH_BYTES);
			return new String(plainBytes, StandardCharsets.UTF_8);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Failed to decrypt stored service credential", ex);
		}
	}
}

