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
import com.expnai.relay.features.beehiiv.BeehiivClient;
import com.expnai.relay.features.promote.higgsfield.HiggsfieldClient;

/**
 * Validates user-supplied provider keys (Gemini, OpenAI, Higgsfield KEY_ID:KEY_SECRET,
 * beehiiv, ElevenLabs) with a lightweight provider probe before saving a connection as CONNECTED.
 */
@Service
public class AiKeyValidationService {

	private static final Logger log = LoggerFactory.getLogger(AiKeyValidationService.class);
	private static final String INVALID_KEY_MESSAGE = "This key is not valid";

	private final HiggsfieldClient higgsfieldClient;
	private final BeehiivClient beehiivClient;

	public AiKeyValidationService(HiggsfieldClient higgsfieldClient, BeehiivClient beehiivClient) {
		this.higgsfieldClient = higgsfieldClient;
		this.beehiivClient = beehiivClient;
	}

	public void validateKey(String serviceId, String rawKey) {
		String key = rawKey != null ? rawKey.trim() : "";
		if (key.length() < 10 || key.contains(" ") || key.contains("\n") || key.contains("\r")) {
			throw new BusinessRuleException(INVALID_KEY_MESSAGE);
		}

		String id = serviceId != null ? serviceId.trim().toLowerCase() : "";
		if ("higgsfield".equals(id) || "hf".equals(id)) {
			higgsfieldClient.probeAuth(HiggsfieldClient.parseCredentials(key));
			return;
		}
		if ("elevenlabs".equals(id) || "eleven".equals(id)) {
			probeElevenLabs(key);
			return;
		}
		if ("beehiiv".equals(id)) {
			beehiivClient.probeApiKey(key);
			return;
		}

		boolean requiresGemini = "llm".equals(id)
				|| "gemini".equals(id)
				|| "google".equals(id)
				|| "google-ai".equals(id)
				|| "img".equals(id)
				|| "image".equals(id)
				|| "gemini-image".equals(id);

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

	private void probeElevenLabs(String key) {
		try {
			HttpClient http = HttpClient.newBuilder()
					.connectTimeout(Duration.ofSeconds(8))
					.build();
			HttpRequest req = HttpRequest.newBuilder()
					.uri(URI.create("https://api.elevenlabs.io/v1/user"))
					.timeout(Duration.ofSeconds(10))
					.header("xi-api-key", key)
					.header("Accept", "application/json")
					.GET()
					.build();
			HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
				log.warn("ElevenLabs key validation probe rejected with HTTP {}", resp.statusCode());
				throw new BusinessRuleException(INVALID_KEY_MESSAGE);
			}
		}
		catch (BusinessRuleException ex) {
			throw ex;
		}
		catch (Exception ex) {
			log.warn("ElevenLabs key validation probe failed: {}", ex.getMessage());
			throw new BusinessRuleException(INVALID_KEY_MESSAGE);
		}
	}
}
