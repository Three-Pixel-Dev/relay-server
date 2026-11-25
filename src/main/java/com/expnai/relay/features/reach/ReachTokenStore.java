package com.expnai.relay.features.reach;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.expnai.relay.common.exception.BusinessRuleException;
import com.expnai.relay.common.security.CredentialEncryptionService;
import com.expnai.relay.domain.entity.RelayServiceConnection;
import com.expnai.relay.domain.repository.RelayServiceConnectionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ReachTokenStore {

	private final CredentialEncryptionService credentialEncryptionService;
	private final RelayServiceConnectionRepository connectionRepository;
	private final ObjectMapper objectMapper;

	public record Tokens(
			String accessToken,
			String refreshToken,
			Instant expiresAt,
			String openId,
			String pageAccessToken,
			String igUserId
	) {
	}

	public void store(RelayServiceConnection conn, Tokens tokens) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("accessToken", tokens.accessToken());
		if (tokens.refreshToken() != null) {
			payload.put("refreshToken", tokens.refreshToken());
		}
		else {
			Tokens existing = readQuiet(conn);
			if (existing != null && existing.refreshToken() != null) {
				payload.put("refreshToken", existing.refreshToken());
			}
		}
		if (tokens.expiresAt() != null) {
			payload.put("expiresAt", tokens.expiresAt().toString());
		}
		if (tokens.openId() != null) {
			payload.put("openId", tokens.openId());
		}
		if (tokens.pageAccessToken() != null) {
			payload.put("pageAccessToken", tokens.pageAccessToken());
		}
		if (tokens.igUserId() != null) {
			payload.put("igUserId", tokens.igUserId());
		}
		try {
			conn.setEncryptedCredential(credentialEncryptionService.encrypt(objectMapper.writeValueAsString(payload)));
		}
		catch (Exception ex) {
			throw new BusinessRuleException("Could not store reach credentials.");
		}
		connectionRepository.save(conn);
	}

	public Tokens require(RelayServiceConnection conn) {
		Tokens tokens = readQuiet(conn);
		if (tokens == null || tokens.accessToken() == null) {
			throw new BusinessRuleException("Reconnect " + conn.getServiceId() + " in Connections.");
		}
		return tokens;
	}

	public Tokens readQuiet(RelayServiceConnection conn) {
		String raw = conn.getEncryptedCredential();
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			String json = credentialEncryptionService.decrypt(raw);
			Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {
			});
			Instant expires = null;
			String exp = str(map.get("expiresAt"));
			if (exp != null) {
				expires = Instant.parse(exp);
			}
			return new Tokens(
					str(map.get("accessToken")),
					str(map.get("refreshToken")),
					expires,
					str(map.get("openId")),
					str(map.get("pageAccessToken")),
					str(map.get("igUserId")));
		}
		catch (Exception ex) {
			return null;
		}
	}

	private static String str(Object value) {
		if (value == null) {
			return null;
		}
		String s = String.valueOf(value).trim();
		return s.isEmpty() ? null : s;
	}
}
