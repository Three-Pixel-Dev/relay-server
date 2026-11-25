package com.expnai.relay.features.reach.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import com.expnai.relay.features.reach.ReachOAuthService;
import com.expnai.relay.features.reach.ReachTokenStore;
import com.expnai.relay.features.reach.ReachTokenStore.Tokens;
import com.expnai.relay.features.reach.config.ReachProperties;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RelayReachService {

	private static final Set<String> SERVICES = Set.of("youtube", "tiktok", "instagram");

	private final ReachOAuthService oauthService;
	private final ReachTokenStore tokenStore;
	private final ReachProperties properties;
	private final RelayUserRepository userRepository;
	private final RelayServiceConnectionRepository connectionRepository;

	@Value("${jwt.secret:relay-dev-secret-change-me-please-32chars-min}")
	private String stateSecret;

	@Transactional(readOnly = true)
	public ApiResponse<Map<String, String>> startConnect(String serviceId) {
		String key = normalize(serviceId);
		RelayUser user = requireCurrentUser();
		if (!oauthService.isConfigured(key)) {
			throw new BusinessRuleException(oauthService.configHint(key));
		}
		String state = signState(user.getId() + ":" + key + ":" + UUID.randomUUID());
		String url = oauthService.buildAuthorizationUrl(key, state);
		return ApiResponse.ok("Authorize " + key, Map.of(
				"authorizationUrl", url,
				"state", state));
	}

	@Transactional
	public ApiResponse<Map<String, Object>> completeConnect(String serviceId, String code, String state) {
		StatePayload parsed = parseState(state);
		String key = normalize(serviceId);
		if (!key.equals(parsed.serviceId())) {
			throw new BusinessRuleException("Invalid OAuth state.");
		}
		RelayUser user = resolveUser(parsed.userId());
		Tokens tokens = oauthService.exchangeCode(key, code);

		RelayServiceConnection conn = connectionRepository
				.findFirstByUserIdAndServiceIdIgnoreCaseOrderByCreatedAtDesc(user.getId(), key)
				.orElseGet(() -> {
					RelayServiceConnection c = new RelayServiceConnection();
					c.setUser(user);
					c.setServiceId(key);
					return c;
				});
		conn.setStatus(RelayServiceStatus.CONNECTED);
		conn.setLabel(labelFor(key, tokens));
		if (tokens.openId() != null) {
			conn.setExternalAccountId(tokens.openId());
		}
		else if (tokens.igUserId() != null) {
			conn.setExternalAccountId(tokens.igUserId());
		}
		tokenStore.store(conn, tokens);

		return ApiResponse.ok(capitalize(key) + " connected", Map.of(
				"status", "connected",
				"label", conn.getLabel() != null ? conn.getLabel() : capitalize(key)));
	}

	@Transactional
	public String completeConnectRedirect(String serviceId, String code, String state) {
		completeConnect(serviceId, code, state);
		return UriComponentsBuilder.fromUriString(properties.getFrontendRedirectBase())
				.path("/connections")
				.queryParam(normalize(serviceId), "connected")
				.build()
				.toUriString();
	}

	private String labelFor(String key, Tokens tokens) {
		return switch (key) {
			case "youtube" -> "YouTube connected";
			case "tiktok" -> tokens.openId() != null ? "TikTok " + tokens.openId() : "TikTok connected";
			case "instagram" -> tokens.igUserId() != null ? "IG " + tokens.igUserId() : "Instagram connected";
			default -> capitalize(key);
		};
	}

	private RelayUser resolveUser(Long stateUserId) {
		try {
			RelayUser current = requireCurrentUser();
			if (!current.getId().equals(stateUserId)) {
				throw new BusinessRuleException("Invalid OAuth state.");
			}
			return current;
		}
		catch (InvalidSessionException ex) {
			return userRepository.findById(stateUserId)
					.orElseThrow(() -> new ResourceNotFoundException("User not found."));
		}
	}

	private RelayUser requireCurrentUser() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof RelayUserPrincipal principal)) {
			throw new InvalidSessionException("Not authenticated");
		}
		return userRepository.findById(principal.getId())
				.orElseThrow(() -> new ResourceNotFoundException("User not found."));
	}

	private String normalize(String serviceId) {
		if (serviceId == null || !SERVICES.contains(serviceId.trim().toLowerCase(Locale.ROOT))) {
			throw new BusinessRuleException("Unknown reach service. Use youtube, tiktok, or instagram.");
		}
		return serviceId.trim().toLowerCase(Locale.ROOT);
	}

	private String signState(String payload) {
		String sig = sha256(payload + "|" + stateSecret).substring(0, 24);
		return payload + "." + sig;
	}

	private StatePayload parseState(String state) {
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
		String[] parts = payload.split(":", 3);
		if (parts.length < 2) {
			throw new BusinessRuleException("Invalid OAuth state.");
		}
		try {
			return new StatePayload(Long.valueOf(parts[0]), parts[1].toLowerCase(Locale.ROOT));
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

	private static String capitalize(String value) {
		if (value == null || value.isBlank()) {
			return "";
		}
		return Character.toUpperCase(value.charAt(0)) + value.substring(1);
	}

	private record StatePayload(Long userId, String serviceId) {
	}
}
