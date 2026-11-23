package com.expnai.relay.features.ai;

import java.math.BigDecimal;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.expnai.relay.common.logging.RelayTrace;
import com.expnai.relay.domain.entity.RelayAiUsageEvent;
import com.expnai.relay.domain.entity.RelayProduct;
import com.expnai.relay.domain.repository.RelayAiUsageEventRepository;
import com.expnai.relay.domain.repository.RelayProductRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;

@Service
public class AiUsageMeter {

	private static final Logger log = LoggerFactory.getLogger(AiUsageMeter.class);

	private final AiPricingService pricingService;
	private final RelayAiUsageEventRepository usageEventRepository;
	private final RelayProductRepository productRepository;
	private final TransactionTemplate requiresNewTx;

	public AiUsageMeter(
			AiPricingService pricingService,
			RelayAiUsageEventRepository usageEventRepository,
			RelayProductRepository productRepository,
			PlatformTransactionManager transactionManager) {
		this.pricingService = pricingService;
		this.usageEventRepository = usageEventRepository;
		this.productRepository = productRepository;
		this.requiresNewTx = new TransactionTemplate(transactionManager);
		this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public BigDecimal recordGeminiChat(
			Long productId,
			Integer buildStep,
			String model,
			GenerateContentResponse response,
			String promptText,
			String responseText,
			String operation) {
		int promptTokens = 0;
		int completionTokens = 0;

		if (response != null && response.usageMetadata().isPresent()) {
			GenerateContentResponseUsageMetadata meta = response.usageMetadata().get();
			promptTokens = meta.promptTokenCount().orElse(0)
					+ meta.toolUsePromptTokenCount().orElse(0);
			// Bill Gemini thoughts tokens as output tokens
			completionTokens = meta.candidatesTokenCount().orElse(0)
					+ meta.thoughtsTokenCount().orElse(0);
		}

		if (promptTokens <= 0) {
			promptTokens = estimateTokensFromChars(promptText);
		}
		if (completionTokens <= 0 && responseText != null && !responseText.isBlank()) {
			completionTokens = estimateTokensFromChars(responseText);
		}

		return recordChat(productId, buildStep, "gemini", model, promptTokens, completionTokens, operation);
	}

	public BigDecimal recordOpenAiChat(
			Long productId,
			Integer buildStep,
			String model,
			JsonNode root,
			String promptText,
			String responseText,
			String operation) {
		int promptTokens = 0;
		int completionTokens = 0;

		if (root != null && root.has("usage")) {
			JsonNode usage = root.path("usage");
			promptTokens = usage.path("prompt_tokens").asInt(0);
			completionTokens = usage.path("completion_tokens").asInt(0);
		}

		if (promptTokens <= 0) {
			promptTokens = estimateTokensFromChars(promptText);
		}
		if (completionTokens <= 0 && responseText != null && !responseText.isBlank()) {
			completionTokens = estimateTokensFromChars(responseText);
		}

		return recordChat(productId, buildStep, "openai", model, promptTokens, completionTokens, operation);
	}

	public BigDecimal recordChat(
			Long productId,
			Integer buildStep,
			String provider,
			String model,
			int promptTokens,
			int completionTokens,
			String operation) {
		return persistUsage(productId, buildStep, provider, model, promptTokens, completionTokens, operation);
	}

	public BigDecimal recordEmbed(
			Long productId,
			Integer buildStep,
			String model,
			int promptTokens,
			String operation) {
		int safePrompt = Math.max(1, promptTokens);
		return persistUsage(productId, buildStep, "gemini", model, safePrompt, 0, operation);
	}

	public static int estimateTokensFromChars(String text) {
		if (text == null || text.isBlank()) {
			return 1;
		}
		return Math.max(1, text.length() / 4);
	}

	private BigDecimal persistUsage(
			Long productId,
			Integer buildStep,
			String provider,
			String model,
			int promptTokens,
			int completionTokens,
			String operation) {
		if (productId == null) {
			return BigDecimal.ZERO;
		}
		int safeIn = Math.max(0, promptTokens);
		int safeOut = Math.max(0, completionTokens);
		String safeProvider = (provider != null && !provider.isBlank()) ? provider : "gemini";
		String safeModel = (model != null && !model.isBlank()) ? model : "unknown";
		String safeOp = (operation != null && !operation.isBlank()) ? operation : "chat";

		Optional<BigDecimal> costOpt = pricingService.calculateCostUsd(safeProvider, safeModel, safeIn, safeOut);
		BigDecimal costUsd = costOpt.orElse(null);

		try {
			requiresNewTx.executeWithoutResult(status -> {
				RelayProduct productRef = productRepository.getReferenceById(productId);
				RelayAiUsageEvent event = new RelayAiUsageEvent();
				event.setProduct(productRef);
				event.setBuildStep(buildStep);
				event.setProvider(safeProvider);
				event.setModel(safeModel);
				event.setOperation(safeOp);
				event.setPromptTokens(safeIn);
				event.setCompletionTokens(safeOut);
				event.setCostUsd(costUsd);
				usageEventRepository.save(event);

				if (costUsd != null && costUsd.signum() > 0) {
					productRepository.addSpend(productId, costUsd);
				}
			});
			RelayTrace.ai("product={} step={} op={} provider={} model={} tokens=({} in / {} out) costUsd={}",
					productId, buildStep, safeOp, safeProvider, safeModel, safeIn, safeOut,
					costUsd != null ? "$" + costUsd : "unavailable");
		}
		catch (Exception ex) {
			log.warn("Failed to record AI usage event for product {} (op={}, model={}): {}",
					productId, safeOp, safeModel, ex.getMessage());
		}

		return costUsd != null ? costUsd : BigDecimal.ZERO;
	}
}

