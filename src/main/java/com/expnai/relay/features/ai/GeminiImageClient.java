package com.expnai.relay.features.ai;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.expnai.relay.common.exception.BusinessRuleException;
import com.google.genai.Client;
import com.google.genai.types.Blob;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.ImageConfig;
import com.google.genai.types.Part;

import lombok.RequiredArgsConstructor;

/**
 * Gemini native image generation client using {@code gemini-3.1-flash-image} via
 * {@code generateContent} with {@code responseModalities: ["IMAGE"]}.
 */
@Service
@RequiredArgsConstructor
public class GeminiImageClient {

	private static final Logger log = LoggerFactory.getLogger(GeminiImageClient.class);

	public static final String MODEL_ID = "gemini-3.1-flash-image";
	private static final List<String> FALLBACK_MODELS = List.of(
			"gemini-3.1-flash-image-preview",
			"gemini-2.5-flash-image");

	private final AiUsageMeter aiUsageMeter;

	public byte[] generatePng(
			String apiKey,
			String prompt,
			String aspectHint,
			Long productId,
			Integer buildStep,
			String operation) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new BusinessRuleException("Missing Gemini API key for image generation.");
		}
		String trimmedKey = apiKey.trim();
		if (trimmedKey.startsWith("sk-")) {
			throw new BusinessRuleException("Image generation requires a Google Gemini API key (AIza…).");
		}

		Client client = Client.builder().apiKey(trimmedKey).build();
		GenerateContentConfig.Builder cfgBuilder = GenerateContentConfig.builder()
				.responseModalities("IMAGE")
				.candidateCount(1);
		if (aspectHint != null && !aspectHint.isBlank()) {
			cfgBuilder.imageConfig(ImageConfig.builder().aspectRatio(aspectHint.trim()).build());
		}
		GenerateContentConfig config = cfgBuilder.build();

		ImageCallResult result = callWithFallback(client, prompt, config);
		GenerateContentResponse response = result.response();

		aiUsageMeter.recordGeminiChat(
				productId,
				buildStep,
				result.model(),
				response,
				prompt,
				"",
				operation != null ? operation : "image_cover");

		byte[] bytes = extractImageBytes(response);
		if (bytes == null || bytes.length == 0) {
			throw new BusinessRuleException("Gemini image model returned no image bytes.");
		}
		return bytes;
	}

	private record ImageCallResult(GenerateContentResponse response, String model) {
	}

	private ImageCallResult callWithFallback(Client client, String prompt, GenerateContentConfig config) {
		try {
			return new ImageCallResult(client.models.generateContent(MODEL_ID, prompt, config), MODEL_ID);
		}
		catch (Exception primaryEx) {
			String msg = primaryEx.getMessage() != null ? primaryEx.getMessage() : "";
			if (msg.contains("404") || msg.toLowerCase().contains("not found")) {
				for (String fallback : FALLBACK_MODELS) {
					try {
						log.info("Image model {} unavailable, trying fallback {}", MODEL_ID, fallback);
						return new ImageCallResult(client.models.generateContent(fallback, prompt, config), fallback);
					}
					catch (Exception ignored) {
					}
				}
			}
			throw new BusinessRuleException("Gemini image generation failed: " + primaryEx.getMessage());
		}
	}

	private byte[] extractImageBytes(GenerateContentResponse response) {
		if (response == null) {
			return null;
		}
		Optional<List<Candidate>> candidatesOpt = response.candidates();
		if (candidatesOpt.isEmpty()) {
			return null;
		}
		for (Candidate candidate : candidatesOpt.get()) {
			Optional<Content> contentOpt = candidate.content();
			if (contentOpt.isEmpty()) {
				continue;
			}
			Optional<List<Part>> partsOpt = contentOpt.get().parts();
			if (partsOpt.isEmpty()) {
				continue;
			}
			for (Part part : partsOpt.get()) {
				Optional<Blob> blobOpt = part.inlineData();
				if (blobOpt.isPresent()) {
					Optional<byte[]> dataOpt = blobOpt.get().data();
					if (dataOpt.isPresent() && dataOpt.get().length > 0) {
						return dataOpt.get();
					}
				}
			}
		}
		return null;
	}
}

