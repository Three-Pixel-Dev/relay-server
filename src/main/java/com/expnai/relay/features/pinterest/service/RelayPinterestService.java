package com.expnai.relay.features.pinterest.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
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
import com.expnai.relay.common.web.ApiResponse;
import com.expnai.relay.domain.entity.RelayServiceConnection;
import com.expnai.relay.domain.entity.RelayUser;
import com.expnai.relay.domain.enums.RelayServiceStatus;
import com.expnai.relay.domain.repository.RelayServiceConnectionRepository;
import com.expnai.relay.domain.repository.RelayUserRepository;
import com.expnai.relay.features.auth.security.RelayUserPrincipal;
import com.expnai.relay.features.pinterest.PinterestApiClient;
import com.expnai.relay.features.pinterest.PinterestApiClient.UserAccount;
import com.expnai.relay.features.pinterest.PinterestTokenService;
import com.expnai.relay.features.pinterest.config.PinterestProperties;
import com.expnai.relay.features.pinterest.oauth.PinterestOAuthService;
import com.expnai.relay.features.pinterest.oauth.PinterestOAuthService.TokenBundle;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RelayPinterestService {

	private final PinterestOAuthService oauthService;
	private final PinterestTokenService tokenService;
	private final PinterestApiClient apiClient;
	private final PinterestProperties properties;
	private final RelayUserRepository userRepository;
	private final RelayServiceConnectionRepository connectionRepository;

	@Value("${jwt.secret:relay-dev-secret-change-me-please-32chars-min}")
	private String stateSecret;

	@Transactional(readOnly = true)
	public ApiResponse<Map<String, String>> startConnect() {
		RelayUser user = requireCurrentUser();
		if (!oauthService.isConfigured()) {
			throw new BusinessRuleException(
					"Pinterest OAuth is not configured. Set PINTEREST_APP_ID, PINTEREST_APP_SECRET, PINTEREST_OAUTH_REDIRECT_URI.");
		}
		String state = signState(user.getId() + ":" + UUID.randomUUID());
		String url = oauthService.buildAuthorizationUrl(state);
		return ApiResponse.ok("Authorize Pinterest", Map.of(
				"authorizationUrl", url,
				"state", state));
	}

	@Transactional
	public ApiResponse<Map<String, Object>> completeConnect(String code, String state) {
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
			user = userRepository.findById(stateUserId)
					.orElseThrow(() -> new ResourceNotFoundException("User not found."));
		}

		TokenBundle bundle = oauthService.exchangeCode(code);
		UserAccount account = apiClient.getUserAccount(bundle.accessToken());
		String label = account.username() != null ? "@" + account.username() : "Pinterest";

		final RelayUser owner = user;
		RelayServiceConnection conn = connectionRepository
				.findFirstByUserIdAndServiceIdIgnoreCaseOrderByCreatedAtDesc(owner.getId(), "pinterest")
				.orElseGet(() -> {
					RelayServiceConnection c = new RelayServiceConnection();
					c.setUser(owner);
					c.setServiceId("pinterest");
					return c;
				});
		conn.setLabel(label);
		conn.setStatus(RelayServiceStatus.CONNECTED);
		conn.setExternalAccountId(account.username());
		tokenService.storeTokens(conn, bundle);

		return ApiResponse.ok("Pinterest connected", Map.of(
				"status", "connected",
				"label", label));
	}

	@Transactional
	public String completeConnectRedirect(String code, String state) {
		completeConnect(code, state);
		return UriComponentsBuilder.fromUriString(properties.getFrontendRedirectBase())
				.path("/connections")
				.queryParam("pinterest", "connected")
				.build()
				.toUriString();
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
		try {
			return Long.valueOf(payload.split(":", 2)[0]);
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
