package com.expnai.relay.features.gmail.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.expnai.relay.common.security.TokenEncryptionService;
import com.expnai.relay.domain.entity.RelayGmailMailbox;
import com.expnai.relay.domain.enums.RelayGmailMailboxStatus;
import com.expnai.relay.domain.repository.RelayGmailMailboxRepository;
import com.expnai.relay.features.gmail.oauth.GmailOAuthService;
import com.expnai.relay.features.gmail.oauth.GmailOAuthService.TokenBundle;
import com.expnai.relay.features.gmail.oauth.GmailReauthRequiredException;

class GmailTokenServiceTest {

	private TokenEncryptionService encryption;
	private GmailOAuthService oauth;
	private GmailTokenService service;

	private static String key(int seed) {
		byte[] raw = new byte[32];
		for (int i = 0; i < raw.length; i++) {
			raw[i] = (byte) (seed + i);
		}
		return Base64.getEncoder().encodeToString(raw);
	}

	@BeforeEach
	void setUp() {
		encryption = new TokenEncryptionService(key(1), 1, "", 0);
		oauth = mock(GmailOAuthService.class);
		service = new GmailTokenService(encryption, oauth, mock(RelayGmailMailboxRepository.class));
	}

	private RelayGmailMailbox mailboxWithRefresh(String refresh) {
		RelayGmailMailbox m = new RelayGmailMailbox();
		m.setStatus(RelayGmailMailboxStatus.CONNECTED);
		m.setEncryptedRefreshToken(encryption.encrypt(refresh));
		return m;
	}

	@Test
	void refreshTokenIsStoredEncryptedNeverPlaintext() {
		RelayGmailMailbox m = new RelayGmailMailbox();
		service.storeTokens(m, new TokenBundle("access-1", "refresh-secret", Instant.now().plusSeconds(3600), "s"));
		assertFalse(m.getEncryptedRefreshToken().contains("refresh-secret"));
		assertTrue(m.getEncryptedRefreshToken().startsWith("enc:v1:"));
		assertEquals("refresh-secret", encryption.decrypt(m.getEncryptedRefreshToken()));
	}

	@Test
	void existingRefreshTokenPreservedWhenGoogleReturnsNone() {
		RelayGmailMailbox m = mailboxWithRefresh("keep-me");
		service.storeTokens(m, new TokenBundle("access-2", null, Instant.now().plusSeconds(3600), null));
		assertEquals("keep-me", encryption.decrypt(m.getEncryptedRefreshToken()));
	}

	@Test
	void expiredAccessTokenIsRefreshed() {
		RelayGmailMailbox m = mailboxWithRefresh("r");
		m.setEncryptedAccessToken(encryption.encrypt("old"));
		m.setAccessTokenExpiresAt(Instant.now().minusSeconds(10));
		when(oauth.refresh("r")).thenReturn(new TokenBundle("fresh", null, Instant.now().plusSeconds(3600), null));
		assertEquals("fresh", service.requireAccessToken(m));
	}

	@Test
	void revokedRefreshTokenMarksReauthRequired() {
		RelayGmailMailbox m = mailboxWithRefresh("r");
		when(oauth.refresh(anyString())).thenThrow(new GmailReauthRequiredException("invalid_grant"));
		assertThrows(GmailReauthRequiredException.class, () -> service.requireAccessToken(m));
		assertEquals(RelayGmailMailboxStatus.REAUTH_REQUIRED, m.getStatus());
		assertNull(m.getEncryptedAccessToken());
	}

	@Test
	void reauthRequiredMailboxIsNotUsed() {
		RelayGmailMailbox m = mailboxWithRefresh("r");
		m.setStatus(RelayGmailMailboxStatus.REAUTH_REQUIRED);
		assertThrows(GmailReauthRequiredException.class, () -> service.requireAccessToken(m));
	}

	@Test
	void wrongKeyFailsSecurelyWithoutLeakingToken() {
		RelayGmailMailbox m = mailboxWithRefresh("super-secret-token");
		TokenEncryptionService other = new TokenEncryptionService(key(50), 1, "", 0);
		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> other.decrypt(m.getEncryptedRefreshToken()));
		assertFalse(String.valueOf(ex.getMessage()).contains("super-secret-token"));
	}
}
