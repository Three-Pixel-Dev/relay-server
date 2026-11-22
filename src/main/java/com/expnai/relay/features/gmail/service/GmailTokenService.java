package com.expnai.relay.features.gmail.service;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.expnai.relay.common.exception.BusinessRuleException;
import com.expnai.relay.common.security.TokenEncryptionService;
import com.expnai.relay.domain.entity.RelayGmailMailbox;
import com.expnai.relay.domain.enums.RelayGmailMailboxStatus;
import com.expnai.relay.domain.repository.RelayGmailMailboxRepository;
import com.expnai.relay.features.gmail.oauth.GmailOAuthService;
import com.expnai.relay.features.gmail.oauth.GmailOAuthService.TokenBundle;
import com.expnai.relay.features.gmail.oauth.GmailReauthRequiredException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class GmailTokenService {

	private final TokenEncryptionService tokenEncryptionService;
	private final GmailOAuthService oauthService;
	private final RelayGmailMailboxRepository mailboxRepository;

	@Transactional
	public String requireAccessToken(RelayGmailMailbox mailbox) {
		if (mailbox.getStatus() == RelayGmailMailboxStatus.REAUTH_REQUIRED
				|| mailbox.getStatus() == RelayGmailMailboxStatus.DISCONNECTED) {
			throw new GmailReauthRequiredException("Mailbox requires re-authorization.");
		}
		if (!tokenEncryptionService.isConfigured()) {
			throw new BusinessRuleException(
					"Token encryption key is not configured. Set RELAY_TOKEN_ENCRYPTION_KEY.");
		}
		Instant expires = mailbox.getAccessTokenExpiresAt();
		if (StringUtils.hasText(mailbox.getEncryptedAccessToken())
				&& expires != null
				&& expires.isAfter(Instant.now().plusSeconds(60))) {
			return tokenEncryptionService.decrypt(mailbox.getEncryptedAccessToken());
		}
		return refreshAndStore(mailbox);
	}

	@Transactional
	public String refreshAndStore(RelayGmailMailbox mailbox) {
		try {
			String refresh = tokenEncryptionService.decrypt(mailbox.getEncryptedRefreshToken());
			TokenBundle bundle = oauthService.refresh(refresh);
			storeTokens(mailbox, bundle);
			mailbox.setStatus(RelayGmailMailboxStatus.CONNECTED);
			mailboxRepository.save(mailbox);
			return bundle.accessToken();
		}
		catch (GmailReauthRequiredException ex) {
			mailbox.setStatus(RelayGmailMailboxStatus.REAUTH_REQUIRED);
			mailbox.setEncryptedAccessToken(null);
			mailbox.setAccessTokenExpiresAt(null);
			mailboxRepository.save(mailbox);
			throw ex;
		}
	}

	public void storeTokens(RelayGmailMailbox mailbox, TokenBundle bundle) {
		String previousRefresh = null;
		if (StringUtils.hasText(mailbox.getEncryptedRefreshToken())) {
			try {
				previousRefresh = tokenEncryptionService.decrypt(mailbox.getEncryptedRefreshToken());
			}
			catch (RuntimeException ignored) {
				previousRefresh = null;
			}
		}
		String refreshToStore = StringUtils.hasText(bundle.refreshToken())
				? bundle.refreshToken()
				: previousRefresh;
		if (!StringUtils.hasText(refreshToStore)) {
			throw new BusinessRuleException("Missing refresh token for Gmail mailbox.");
		}
		mailbox.setEncryptedRefreshToken(tokenEncryptionService.encrypt(refreshToStore));
		mailbox.setEncryptedAccessToken(tokenEncryptionService.encrypt(bundle.accessToken()));
		mailbox.setAccessTokenExpiresAt(bundle.accessTokenExpiresAt());
		mailbox.setEncryptionKeyVersion(tokenEncryptionService.currentKeyVersion());
		if (StringUtils.hasText(bundle.scope())) {
			mailbox.setScopes(bundle.scope());
		}
	}
}
