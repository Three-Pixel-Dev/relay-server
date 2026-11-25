package com.expnai.relay.features.pinterest;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.expnai.relay.common.exception.BusinessRuleException;
import com.expnai.relay.common.security.CredentialEncryptionService;
import com.expnai.relay.domain.entity.RelayServiceConnection;
import com.expnai.relay.domain.repository.RelayServiceConnectionRepository;
import com.expnai.relay.features.pinterest.oauth.PinterestOAuthService;
import com.expnai.relay.features.pinterest.oauth.PinterestOAuthService.TokenBundle;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PinterestTokenService {

	private final CredentialEncryptionService credentialEncryptionService;
	private final RelayServiceConnectionRepository connectionRepository;
	private final PinterestOAuthService oauthService;
	private final ObjectMapper objectMapper;

	public void storeTokens(RelayServiceConnection conn, TokenBundle bundle) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("accessToken", bundle.accessToken());
		if (bundle.refreshToken() != null) {
			payload.put("refreshToken", bundle.refreshToken());
		}
		else {
			// Preserve existing refresh on reconnect if Google-style omit
			TokenBundle existing = readQuiet(conn);
			if (existing != null && existing.refreshToken() != null) {
				payload.put("refreshToken", existing.refreshToken());
			}
		}
		payload.put("expiresAt", bundle.accessTokenExpiresAt().toString());
		try {
			conn.setEncryptedCredential(credentialEncryptionService.encrypt(objectMapper.writeValueAsString(payload)));
		}
		catch (Exception ex) {
			throw new BusinessRuleException("Could not store Pinterest credentials.");
		}
		if (bundle.scope() != null) {
			conn.setScopes(bundle.scope());
		}
		connectionRepository.save(conn);
	}

	public String requireAccessToken(RelayServiceConnection conn) {
		TokenBundle bundle = readQuiet(conn);
		if (bundle == null || bundle.accessToken() == null) {
			throw new BusinessRuleException("Pinterest is not connected. Connect it in Connections.");
		}
		if (bundle.accessTokenExpiresAt() != null
				&& bundle.accessTokenExpiresAt().isAfter(Instant.now().plusSeconds(120))) {
			return bundle.accessToken();
		}
		if (bundle.refreshToken() == null || bundle.refreshToken().isBlank()) {
			throw new BusinessRuleException("Pinterest authorization expired. Reconnect in Connections.");
		}
		TokenBundle refreshed = oauthService.refresh(bundle.refreshToken());
		storeTokens(conn, refreshed);
		return refreshed.accessToken();
	}

	private TokenBundle readQuiet(RelayServiceConnection conn) {
		String raw = conn.getEncryptedCredential();
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			String json = credentialEncryptionService.decrypt(raw);
			Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {
			});
			String access = stringVal(map.get("accessToken"));
			String refresh = stringVal(map.get("refreshToken"));
			Instant expires = null;
			String exp = stringVal(map.get("expiresAt"));
			if (exp != null) {
				expires = Instant.parse(exp);
			}
			return new TokenBundle(access, refresh, expires, conn.getScopes());
		}
		catch (Exception ex) {
			return null;
		}
	}

	private static String stringVal(Object value) {
		if (value == null) {
			return null;
		}
		String s = String.valueOf(value).trim();
		return s.isEmpty() ? null : s;
	}
}
