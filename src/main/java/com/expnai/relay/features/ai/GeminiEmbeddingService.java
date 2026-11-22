package com.expnai.relay.features.ai;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.expnai.relay.common.exception.BusinessRuleException;
import com.google.genai.Client;
import com.google.genai.types.ContentEmbedding;
import com.google.genai.types.ContentEmbeddingStatistics;
import com.google.genai.types.EmbedContentConfig;
import com.google.genai.types.EmbedContentResponse;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class GeminiEmbeddingService {

	private static final Logger log = LoggerFactory.getLogger(GeminiEmbeddingService.class);

	public static final String MODEL_ID = "gemini-embedding-2";
	private static final List<String> FALLBACK_MODELS = List.of("gemini-embedding-2-preview", "gemini-embedding-001");
	public static final int DIMENSIONS = 1536;

	private final AiUsageMeter aiUsageMeter;

	public String formatDocumentText(String sourceName, String chunkText) {
		String title = (sourceName == null || sourceName.isBlank()) ? "Untitled" : sourceName.trim();
		return "title: " + title + " | text: " + chunkText;
	}

	public String formatQueryText(String queryText) {
		String q = (queryText == null || queryText.isBlank()) ? "core themes niche transformation audience voice" : queryText.trim();
		return "task: search result | query: " + q;
	}

	public float[] embedDocument(String apiKey, String sourceName, String chunkText) {
		return embedDocument(apiKey, sourceName, chunkText, null, null);
	}

	public float[] embedDocument(String apiKey, String sourceName, String chunkText, Long productId, Integer buildStep) {
		return embedFormattedText(apiKey, formatDocumentText(sourceName, chunkText), productId, buildStep, "embed_doc");
	}

	public float[] embedQuery(String apiKey, String queryText) {
		return embedQuery(apiKey, queryText, null, null);
	}

	public float[] embedQuery(String apiKey, String queryText, Long productId, Integer buildStep) {
		return embedFormattedText(apiKey, formatQueryText(queryText), productId, buildStep, "embed_query");
	}

	public String embedDocumentAsVectorLiteral(String apiKey, String sourceName, String chunkText) {
		return embedDocumentAsVectorLiteral(apiKey, sourceName, chunkText, null, null);
	}

	public String embedDocumentAsVectorLiteral(String apiKey, String sourceName, String chunkText, Long productId, Integer buildStep) {
		return toVectorLiteral(embedDocument(apiKey, sourceName, chunkText, productId, buildStep));
	}

	public String embedQueryAsVectorLiteral(String apiKey, String queryText) {
		return embedQueryAsVectorLiteral(apiKey, queryText, null, null);
	}

	public String embedQueryAsVectorLiteral(String apiKey, String queryText, Long productId, Integer buildStep) {
		return toVectorLiteral(embedQuery(apiKey, queryText, productId, buildStep));
	}

	public float[] embedFormattedText(String apiKey, String formattedText) {
		return embedFormattedText(apiKey, formattedText, null, null, "embed");
	}

	public float[] embedFormattedText(
			String apiKey,
			String formattedText,
			Long productId,
			Integer buildStep,
			String operation) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new BusinessRuleException("Missing Gemini API key for embeddings.");
		}
		Client client = Client.builder().apiKey(apiKey.trim()).build();
		EmbedContentConfig config = EmbedContentConfig.builder()
				.outputDimensionality(DIMENSIONS)
				.build();

		EmbedCallResult callResult = callWithFallback(client, formattedText, config);
		EmbedContentResponse response = callResult.response();
		List<ContentEmbedding> embeddings = response.embeddings().orElse(List.of());
		if (embeddings.isEmpty() || embeddings.get(0).values().isEmpty()) {
			throw new BusinessRuleException("Gemini embedding response returned no vector values.");
		}

		ContentEmbedding first = embeddings.get(0);
		int tokenCount = first.statistics()
				.flatMap(ContentEmbeddingStatistics::tokenCount)
				.map(f -> Math.max(1, Math.round(f)))
				.orElseGet(() -> AiUsageMeter.estimateTokensFromChars(formattedText));
		aiUsageMeter.recordEmbed(productId, buildStep, callResult.model(), tokenCount, operation);

		List<Float> rawValues = first.values().get();
		if (rawValues.size() != DIMENSIONS) {
			throw new BusinessRuleException(
					"Expected embedding dimensionality " + DIMENSIONS + " but got " + rawValues.size());
		}

		float[] vector = new float[DIMENSIONS];
		double normSq = 0.0;
		for (int i = 0; i < DIMENSIONS; i++) {
			float v = rawValues.get(i) != null ? rawValues.get(i) : 0.0f;
			vector[i] = v;
			normSq += (double) v * v;
		}
		double norm = Math.sqrt(normSq);
		if (norm > 1e-12) {
			for (int i = 0; i < DIMENSIONS; i++) {
				vector[i] = (float) (vector[i] / norm);
			}
		}
		return vector;
	}

	private record EmbedCallResult(EmbedContentResponse response, String model) {
	}

	private EmbedCallResult callWithFallback(Client client, String text, EmbedContentConfig config) {
		try {
			return new EmbedCallResult(client.models.embedContent(MODEL_ID, text, config), MODEL_ID);
		}
		catch (Exception primaryEx) {
			String msg = primaryEx.getMessage() != null ? primaryEx.getMessage() : "";
			if (msg.contains("404") || msg.toLowerCase().contains("not found")) {
				for (String fallbackModel : FALLBACK_MODELS) {
					try {
						log.info("Model {} unavailable for key, trying fallback {}", MODEL_ID, fallbackModel);
						return new EmbedCallResult(client.models.embedContent(fallbackModel, text, config), fallbackModel);
					}
					catch (Exception ignored) {
						// Continue trying next fallback
					}
				}
			}
			throw new BusinessRuleException("Gemini embedding failed: " + primaryEx.getMessage());
		}
	}

	public static String toVectorLiteral(float[] vector) {
		StringBuilder sb = new StringBuilder(vector.length * 10 + 2);
		sb.append('[');
		for (int i = 0; i < vector.length; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(vector[i]);
		}
		sb.append(']');
		return sb.toString();
	}
}

