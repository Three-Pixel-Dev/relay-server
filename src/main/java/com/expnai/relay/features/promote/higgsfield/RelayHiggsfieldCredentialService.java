package com.expnai.relay.features.promote.higgsfield;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.expnai.relay.common.exception.BusinessRuleException;
import com.expnai.relay.common.security.CredentialEncryptionService;
import com.expnai.relay.domain.entity.RelayServiceConnection;
import com.expnai.relay.domain.enums.RelayServiceStatus;
import com.expnai.relay.domain.repository.RelayServiceConnectionRepository;
import com.expnai.relay.features.promote.higgsfield.HiggsfieldClient.Credentials;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RelayHiggsfieldCredentialService {

	private static final Logger log = LoggerFactory.getLogger(RelayHiggsfieldCredentialService.class);
	private static final List<String> SERVICE_IDS = List.of("higgsfield", "hf");

	private final RelayServiceConnectionRepository serviceConnectionRepository;
	private final CredentialEncryptionService credentialEncryptionService;

	/** Optional env fallback: {@code KEY_ID:KEY_SECRET}. */
	@Value("${HIGGSFIELD_API_CREDENTIAL:}")
	private String fallbackCredential;

	public Credentials requireCredentials(Long userId) {
		for (String serviceId : SERVICE_IDS) {
			Optional<RelayServiceConnection> conn = serviceConnectionRepository
					.findFirstByUserIdAndServiceIdIgnoreCaseAndStatusOrderByCreatedAtDesc(
							userId, serviceId, RelayServiceStatus.CONNECTED);
			if (conn.isPresent()) {
				String key = readStoredKey(conn.get());
				if (key != null) {
					return HiggsfieldClient.parseCredentials(key);
				}
			}
		}
		String env = trimToNull(fallbackCredential);
		if (env != null) {
			return HiggsfieldClient.parseCredentials(env);
		}
		throw new BusinessRuleException("Add your Higgsfield key in Connections first.");
	}

	private String readStoredKey(RelayServiceConnection connection) {
		String rawStored = connection.getEncryptedCredential();
		String key;
		try {
			key = trimToNull(credentialEncryptionService.decrypt(rawStored));
		}
		catch (RuntimeException ex) {
			log.warn(
					"Stored {} API key could not be decrypted. Re-save it in Connections. {}",
					connection.getServiceId(),
					ex.getMessage());
			return null;
		}
		if (key == null) {
			return null;
		}
		if (rawStored != null && !rawStored.startsWith("enc:v1:")) {
			connection.setEncryptedCredential(credentialEncryptionService.encrypt(key));
			serviceConnectionRepository.save(connection);
		}
		return key;
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
