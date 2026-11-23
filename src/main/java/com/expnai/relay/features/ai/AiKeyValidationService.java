package com.expnai.relay.features.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.expnai.relay.common.exception.BusinessRuleException;

/**
 * Validates user-supplied AI provider keys (Gemini AIza.../AQ.... or OpenAI sk-...)
 * with a lightweight provider probe before saving a connection as CONNECTED.
 */
@Service
public class AiKeyValidationService {

	private static final Logger log = LoggerFactory.getLogger(AiKeyValidationService.class);
	private static final String INVALID_KEY_MESSAGE = "This key is not valid";

	public void validateKey(String serviceId, String rawKey) {
		String key = rawKey != null ? rawKey.trim() : "";
		if (key.length() < 10 || key.contains(" ") || key.contains("\n") || key.contains("\r")) {
			throw new BusinessRuleException(INVALID_KEY_MESSAGE);
		}

		boolean requiresGemini = "llm".equalsIgnoreCase(serviceId)
				|| "gemini".equalsIgnoreCase(serviceId)
				|| "google".equalsIgnoreCase(serviceId)
				|| "google-ai".equalsIgnoreCase(serviceId)
				|| "img".equalsIgnoreCase(serviceId)
				|| "image".equalsIgnoreCase(serviceId)
				|| "gemini-image".equalsIgnoreCase(serviceId);

		if (requiresGemini && key.startsWith("sk-")) {
			throw new BusinessRuleException(
					"Requires a Google Gemini API key (starts with AIza… or AQ.…), not an OpenAI sk-… key.");
		}

		if (key.startsWith("sk-")) {
			probeOpenAi(key);
		}
		else {
			probeGemini(key);
		}
	}

	private void probeGemini(String key) {
		try {
			HttpClient http = HttpClient.newBuilder()
					.connectTimeout(Duration.ofSeconds(8))
					.build();
			HttpRequest req = HttpRequest.newBuilder()
					.uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1"))
					.timeout(Duration.ofSeconds(10))
					.header("x-goog-api-key", key)
					.header("Accept", "application/json")
					.GET()
					.build();
			HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
				log.warn("Gemini key validation probe rejected with HTTP {}", resp.statusCode());
				throw new BusinessRuleException(INVALID_KEY_MESSAGE);
			}
		}
		catch (BusinessRuleException ex) {
			throw ex;
		}
		catch (Exception ex) {
			log.warn("Gemini key validation probe failed: {}", ex.getMessage());
			throw new BusinessRuleException(INVALID_KEY_MESSAGE);
		}
	}

	private void probeOpenAi(String key) {
		try {
			HttpClient http = HttpClient.newBuilder()
					.connectTimeout(Duration.ofSeconds(8))
					.build();
			HttpRequest req = HttpRequest.newBuilder()
					.uri(URI.create("https://api.openai.com/v1/models"))
					.timeout(Duration.ofSeconds(10))
					.header("Authorization", "Bearer " + key)
					.header("Accept", "application/json")
					.GET()
					.build();
			HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
				log.warn("OpenAI key validation probe rejected with HTTP {}", resp.statusCode());
				throw new BusinessRuleException(INVALID_KEY_MESSAGE);
			}
		}
		catch (BusinessRuleException ex) {
			throw ex;
		}
		catch (Exception ex) {
			log.warn("OpenAI key validation probe failed: {}", ex.getMessage());
			throw new BusinessRuleException(INVALID_KEY_MESSAGE);
		}
	}
}

