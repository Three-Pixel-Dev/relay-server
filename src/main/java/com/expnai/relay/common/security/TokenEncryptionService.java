package com.expnai.relay.common.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.AEADBadTagException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * AES-256-GCM encryption for OAuth refresh/access tokens (and other secrets).
 * <p>
 * Wire format: {@code enc:v{version}:{base64(12-byte-IV || ciphertext || 128-bit tag)}}.
 * <p>
 * Keys are 32 raw bytes, supplied as Base64 via env/config. Never log plaintext or decrypted tokens.
 * Key backup/rotation: see {@code docs/token-encryption.md}.
 */
@Service
public class TokenEncryptionService {

	private static final Logger log = LoggerFactory.getLogger(TokenEncryptionService.class);

	private static final String ALGORITHM = "AES/GCM/NoPadding";
	private static final String PREFIX = "enc:v";
	private static final int IV_LENGTH_BYTES = 12;
	private static final int TAG_LENGTH_BITS = 128;
	private static final int KEY_LENGTH_BYTES = 32;

	private final SecureRandom secureRandom = new SecureRandom();
	private final int currentKeyVersion;
	private final Map<Integer, SecretKeySpec> keysByVersion;

	public TokenEncryptionService(
			@Value("${relay.token-encryption.key:}") String currentKeyBase64,
			@Value("${relay.token-encryption.key-version:1}") int currentKeyVersion,
			@Value("${relay.token-encryption.previous-key:}") String previousKeyBase64,
			@Value("${relay.token-encryption.previous-key-version:0}") int previousKeyVersion) {
		this.currentKeyVersion = currentKeyVersion;
		this.keysByVersion = new LinkedHashMap<>();
		if (StringUtils.hasText(currentKeyBase64)) {
			keysByVersion.put(currentKeyVersion, toAesKey(currentKeyBase64, "relay.token-encryption.key"));
		}
		if (StringUtils.hasText(previousKeyBase64) && previousKeyVersion > 0) {
			keysByVersion.put(previousKeyVersion, toAesKey(previousKeyBase64, "relay.token-encryption.previous-key"));
		}
	}

	public boolean isConfigured() {
		return keysByVersion.containsKey(currentKeyVersion);
	}

	public int currentKeyVersion() {
		return currentKeyVersion;
	}

	/**
	 * Encrypts plaintext with a fresh SecureRandom IV. Returns null for blank input.
	 */
	public String encrypt(String plaintext) {
		if (!StringUtils.hasText(plaintext)) {
			return null;
		}
		SecretKeySpec key = requireCurrentKey();
		try {
			byte[] iv = new byte[IV_LENGTH_BYTES];
			secureRandom.nextBytes(iv);

			Cipher cipher = Cipher.getInstance(ALGORITHM);
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
			byte[] cipherBytes = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

			ByteBuffer buf = ByteBuffer.allocate(iv.length + cipherBytes.length);
			buf.put(iv);
			buf.put(cipherBytes);
			return PREFIX + currentKeyVersion + ":" + Base64.getEncoder().encodeToString(buf.array());
		}
		catch (GeneralSecurityException ex) {
			log.error("Token encryption failed: {}", ex.getClass().getSimpleName());
			throw new IllegalStateException("Token encryption failed", ex);
		}
	}

	/**
	 * Decrypts an {@code enc:vN:...} payload. Fails securely on tamper/wrong key (never returns partial secrets).
	 */
	public String decrypt(String storedValue) {
		if (!StringUtils.hasText(storedValue)) {
			return null;
		}
		String trimmed = storedValue.trim();
		if (!trimmed.startsWith(PREFIX)) {
			throw new IllegalStateException("Stored token is not in encrypted enc:vN format");
		}
		int colon = trimmed.indexOf(':', PREFIX.length());
		if (colon < 0) {
			throw new IllegalStateException("Invalid encrypted token format");
		}
		int version;
		try {
			version = Integer.parseInt(trimmed.substring(PREFIX.length(), colon));
		}
		catch (NumberFormatException ex) {
			throw new IllegalStateException("Invalid encryption key version in token payload", ex);
		}
		SecretKeySpec key = keysByVersion.get(version);
		if (key == null) {
			log.error("No encryption key configured for version={}", version);
			throw new IllegalStateException("Encryption key version " + version + " is not available");
		}
		try {
			byte[] raw = Base64.getDecoder().decode(trimmed.substring(colon + 1));
			if (raw.length <= IV_LENGTH_BYTES) {
				throw new IllegalStateException("Invalid encrypted token payload length");
			}
			Cipher cipher = Cipher.getInstance(ALGORITHM);
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, raw, 0, IV_LENGTH_BYTES));
			byte[] plain = cipher.doFinal(raw, IV_LENGTH_BYTES, raw.length - IV_LENGTH_BYTES);
			return new String(plain, StandardCharsets.UTF_8);
		}
		catch (AEADBadTagException ex) {
			log.error("Token decryption authentication failed for keyVersion={}", version);
			throw new IllegalStateException("Token decryption failed: authentication tag mismatch", ex);
		}
		catch (GeneralSecurityException | IllegalArgumentException ex) {
			log.error("Token decryption failed for keyVersion={}: {}", version, ex.getClass().getSimpleName());
			throw new IllegalStateException("Token decryption failed", ex);
		}
	}

	private SecretKeySpec requireCurrentKey() {
		SecretKeySpec key = keysByVersion.get(currentKeyVersion);
		if (key == null) {
			throw new IllegalStateException(
					"Token encryption key is not configured. Set RELAY_TOKEN_ENCRYPTION_KEY to a Base64-encoded 32-byte secret.");
		}
		return key;
	}

	private static SecretKeySpec toAesKey(String base64, String label) {
		byte[] decoded;
		try {
			decoded = Base64.getDecoder().decode(base64.trim());
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException(label + " must be Base64-encoded", ex);
		}
		if (decoded.length != KEY_LENGTH_BYTES) {
			throw new IllegalStateException(
					label + " must decode to exactly 32 bytes (AES-256). Got " + decoded.length + " bytes.");
		}
		return new SecretKeySpec(decoded, "AES");
	}
}
