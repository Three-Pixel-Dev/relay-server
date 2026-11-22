package com.expnai.relay.features.gmail.serviceImpl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import com.expnai.relay.common.exception.BusinessRuleException;
import com.expnai.relay.common.exception.InvalidSessionException;
import com.expnai.relay.common.exception.ResourceNotFoundException;
import com.expnai.relay.common.security.TokenEncryptionService;
import com.expnai.relay.common.web.ApiResponse;
import com.expnai.relay.domain.entity.RelayGmailMailbox;
import com.expnai.relay.domain.entity.RelayUser;
import com.expnai.relay.domain.enums.RelayGmailMailboxStatus;
import com.expnai.relay.domain.repository.RelayGmailMailboxRepository;
import com.expnai.relay.domain.repository.RelayUserRepository;
import com.expnai.relay.features.auth.security.RelayUserPrincipal;
import com.expnai.relay.features.gmail.client.GmailApiClient;
import com.expnai.relay.features.gmail.dto.response.RelayGmailAuthUrlResponse;
import com.expnai.relay.features.gmail.dto.response.RelayGmailMailboxResponse;
import com.expnai.relay.features.gmail.oauth.GmailOAuthService;
import com.expnai.relay.features.gmail.oauth.GmailOAuthService.TokenBundle;
import com.expnai.relay.features.gmail.service.GmailTokenService;
import com.expnai.relay.features.gmail.service.RelayGmailMailboxService;
import com.expnai.relay.features.gmail.sync.GmailHistorySyncService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RelayGmailMailboxServiceImpl implements RelayGmailMailboxService {

	private final RelayUserRepository userRepository;
	private final RelayGmailMailboxRepository mailboxRepository;
	private final GmailOAuthService oauthService;
	private final GmailTokenService tokenService;
	private final TokenEncryptionService tokenEncryptionService;
	private final GmailApiClient gmailApiClient;
	private final GmailHistorySyncService historySyncService;

	@Value("${jwt.secret:relay-dev-secret-change-me-please-32chars-min}")
	private String stateSecret;

	@Override
	@Transactional(readOnly = true)
	public ApiResponse<List<RelayGmailMailboxResponse>> listMine() {
		RelayUser user = requireCurrentUser();
		List<RelayGmailMailboxResponse> list = mailboxRepository.findByUserIdOrderByCreatedAtAsc(user.getId())
				.stream()
				.map(this::toResponse)
				.toList();
		return ApiResponse.ok("OK", list);
	}

	@Override
	@Transactional(readOnly = true)
	public ApiResponse<RelayGmailMailboxResponse> get(Long mailboxId) {
		RelayUser user = requireCurrentUser();
		RelayGmailMailbox mailbox = requireOwned(user, mailboxId);
		return ApiResponse.ok("OK", toResponse(mailbox));
	}

	@Override
	@Transactional(readOnly = true)
	public ApiResponse<RelayGmailAuthUrlResponse> startConnect() {
		RelayUser user = requireCurrentUser();
		if (!oauthService.isConfigured()) {
			throw new BusinessRuleException(
					"Gmail OAuth is not configured. Set GMAIL_OAUTH_CLIENT_ID / SECRET / REDIRECT_URI.");
		}
		if (!tokenEncryptionService.isConfigured()) {
			throw new BusinessRuleException(
					"Token encryption key is not configured. Set RELAY_TOKEN_ENCRYPTION_KEY (Base64 32 bytes).");
		}
		String state = signState(user.getId() + ":" + UUID.randomUUID());
		String url = oauthService.buildAuthorizationUrl(state);
		return ApiResponse.ok("Authorize Gmail", new RelayGmailAuthUrlResponse(url, state));
	}

	@Override
	@Transactional
	public ApiResponse<RelayGmailMailboxResponse> completeConnect(String code, String state) {
		Long stateUserId = parseStateUserId(state);
		RelayUser user;
		try {
			RelayUser current = requireCurrentUser();
			if (!current.getId().equals(stateUserId)) {
				throw new BusinessRuleException("Invalid OAuth state.");
			}
			user = current;
		}
		catch (InvalidSessionException ex) {
			// Google browser redirect has no JWT — trust signed state user id.
			user = userRepository.findById(stateUserId)
					.orElseThrow(() -> new ResourceNotFoundException("User not found."));
		}
		TokenBundle bundle = oauthService.exchangeCode(code);
		String access = bundle.accessToken();
		var profile = gmailApiClient.getProfile(access);
		String email = profile.email().trim().toLowerCase(Locale.ROOT);
		final RelayUser owner = user;

		RelayGmailMailbox mailbox = mailboxRepository.findByUserIdAndEmailIgnoreCase(owner.getId(), email)
				.orElseGet(() -> {
					RelayGmailMailbox m = new RelayGmailMailbox();
					m.setUser(owner);
					m.setEmail(email);
					return m;
				});

		mailbox.setLabel(email);
		mailbox.setStatus(RelayGmailMailboxStatus.CONNECTED);
		tokenService.storeTokens(mailbox, bundle);
		// Preserve refresh token if Google omitted a new one on reconnect — storeTokens already handles that.
		mailbox.setHistoryId(profile.historyId());
		mailbox.setLastSyncAt(Instant.now());
		mailbox.setLastSyncError(null);
		mailbox = mailboxRepository.save(mailbox);

		// Safe initial sync: baseline historyId only (no silent skip of future changes).
		historySyncService.syncMailboxSafe(mailbox.getId());

		return ApiResponse.ok("Gmail connected", toResponse(mailbox));
	}

	@Override
	@Transactional
	public ApiResponse<Void> disconnect(Long mailboxId) {
		RelayUser user = requireCurrentUser();
		RelayGmailMailbox mailbox = requireOwned(user, mailboxId);
		mailbox.setStatus(RelayGmailMailboxStatus.DISCONNECTED);
		mailbox.setEncryptedAccessToken(null);
		mailbox.setEncryptedRefreshToken(null);
		mailbox.setAccessTokenExpiresAt(null);
		mailbox.setHistoryId(null);
		mailboxRepository.save(mailbox);
		return ApiResponse.ok("Gmail disconnected");
	}

	/**
	 * Browser redirect landing: completes connect for the signed-in user.
	 */
	@Transactional
	public String completeConnectRedirect(String code, String state, String frontendBase) {
		completeConnect(code, state);
		return UriComponentsBuilder.fromUriString(frontendBase)
				.path("/connections")
				.queryParam("gmail", "connected")
				.build()
				.toUriString();
	}

	private RelayGmailMailboxResponse toResponse(RelayGmailMailbox mailbox) {
		return new RelayGmailMailboxResponse(
				String.valueOf(mailbox.getId()),
				mailbox.getEmail(),
				mailbox.getLabel(),
				mailbox.getStatus() != null ? mailbox.getStatus().getCode() : "DISCONNECTED",
				mailbox.getHistoryId(),
				mailbox.getLastSyncAt() != null ? mailbox.getLastSyncAt().toString() : null,
				mailbox.getLastSyncError(),
				mailbox.getStatus() == RelayGmailMailboxStatus.REAUTH_REQUIRED);
	}

	private RelayGmailMailbox requireOwned(RelayUser user, Long mailboxId) {
		return mailboxRepository.findByIdAndUserId(mailboxId, user.getId())
				.orElseThrow(() -> new ResourceNotFoundException("Gmail mailbox not found."));
	}

	private RelayUser requireCurrentUser() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof RelayUserPrincipal principal)) {
			throw new InvalidSessionException("Not authenticated");
		}
		return userRepository.findById(principal.getId())
				.orElseThrow(() -> new ResourceNotFoundException("User not found."));
	}

	private String signState(String payload) {
		String sig = sha256(payload + "|" + stateSecret).substring(0, 24);
		return payload + "." + sig;
	}

	private Long parseStateUserId(String state) {
		if (!StringUtils.hasText(state) || !state.contains(".")) {
			throw new BusinessRuleException("Invalid OAuth state.");
		}
		int dot = state.lastIndexOf('.');
		String payload = state.substring(0, dot);
		String sig = state.substring(dot + 1);
		String expected = sha256(payload + "|" + stateSecret).substring(0, 24);
		if (!expected.equals(sig)) {
			throw new BusinessRuleException("Invalid OAuth state signature.");
		}
		String userPart = payload.split(":", 2)[0];
		try {
			return Long.valueOf(userPart);
		}
		catch (NumberFormatException ex) {
			throw new BusinessRuleException("Invalid OAuth state.");
		}
	}

	private static String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}
}
