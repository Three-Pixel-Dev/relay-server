package com.expnai.relay.features.ai;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.expnai.relay.common.exception.BusinessRuleException;
import com.expnai.relay.common.security.CredentialEncryptionService;
import com.expnai.relay.domain.entity.RelayServiceConnection;
import com.expnai.relay.domain.enums.RelayServiceStatus;
import com.expnai.relay.domain.repository.RelayServiceConnectionRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RelayAiCredentialService {

	private static final List<String> GEMINI_SERVICE_IDS = List.of("gemini", "google", "google-ai", "llm");
	private static final List<String> IMAGE_SERVICE_IDS = List.of("img", "image", "gemini-image");

	private final RelayServiceConnectionRepository serviceConnectionRepository;
	private final CredentialEncryptionService credentialEncryptionService;

	@Value("${GEMINI_API_KEY:${GOOGLE_API_KEY:}}")
	private String fallbackGeminiKey;

	public String requireGeminiEmbeddingKey(Long userId) {
		return resolveGeminiKey(userId);
	}

	public String requireChatModelKey(Long userId) {
		return resolveGeminiKey(userId);
	}

	public String requireImageModelKey(Long userId) {
		boolean sawOpenAiKey = false;
		for (String serviceId : IMAGE_SERVICE_IDS) {
			Optional<RelayServiceConnection> conn = serviceConnectionRepository
					.findFirstByUserIdAndServiceIdIgnoreCaseAndStatusOrderByCreatedAtDesc(
							userId,
							serviceId,
							RelayServiceStatus.CONNECTED);
			if (conn.isPresent()) {
				RelayServiceConnection connection = conn.get();
				String rawStored = connection.getEncryptedCredential();
				String key = trimToNull(credentialEncryptionService.decrypt(rawStored));
				if (key != null) {
					if (rawStored != null && !rawStored.startsWith("enc:v1:")) {
						connection.setEncryptedCredential(credentialEncryptionService.encrypt(key));
						serviceConnectionRepository.save(connection);
					}
					if (key.startsWith("sk-")) {
						sawOpenAiKey = true;
						continue;
					}
					return key;
				}
			}
		}
		String envKey = trimToNull(fallbackGeminiKey);
		if (envKey != null && !envKey.startsWith("sk-")) {
			return envKey;
		}
		if (sawOpenAiKey) {
			throw new BusinessRuleException(
					"Image model requires a Google Gemini API key (starts with AIza…). Update your Image model key in Connections.");
		}
		throw new BusinessRuleException(
				"Connect your Google Gemini API key (under Image model in Connections) or set GEMINI_API_KEY before generating covers and page graphics.");
	}

	public Optional<String> resolveOptionalImageModelKey(Long userId) {
		try {
			return Optional.of(requireImageModelKey(userId));
		}
		catch (BusinessRuleException ignored) {
			return Optional.empty();
		}
	}

	private String resolveGeminiKey(Long userId) {
		boolean sawOpenAiKey = false;
		for (String serviceId : GEMINI_SERVICE_IDS) {
			Optional<RelayServiceConnection> conn = serviceConnectionRepository
					.findFirstByUserIdAndServiceIdIgnoreCaseAndStatusOrderByCreatedAtDesc(
							userId,
							serviceId,
							RelayServiceStatus.CONNECTED);
			if (conn.isPresent()) {
				RelayServiceConnection connection = conn.get();
				String rawStored = connection.getEncryptedCredential();
				String key = trimToNull(credentialEncryptionService.decrypt(rawStored));
				if (key != null) {
					if (rawStored != null && !rawStored.startsWith("enc:v1:")) {
						connection.setEncryptedCredential(credentialEncryptionService.encrypt(key));
						serviceConnectionRepository.save(connection);
					}
					if (key.startsWith("sk-")) {
						sawOpenAiKey = true;
						continue;
					}
					return key;
				}
			}
		}
		String envKey = trimToNull(fallbackGeminiKey);
		if (envKey != null && !envKey.startsWith("sk-")) {
			return envKey;
		}
		if (sawOpenAiKey) {
			throw new BusinessRuleException(
					"Text model currently has an OpenAI (sk-…) key, but source indexing & summary require a Google Gemini API key (starts with AIza…). Update your Text model key in Connections.");
		}
		throw new BusinessRuleException(
				"Connect your Google Gemini API key (under Text model in Connections) or set GEMINI_API_KEY before reading sources.");
	}

	private String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
