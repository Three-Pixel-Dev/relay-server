package com.expnai.relay.features.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.expnai.relay.domain.entity.RelayProduct;
import com.expnai.relay.domain.enums.RelayProductStage;
import com.expnai.relay.features.ai.AiModelPricingCatalog.ModelRates;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AiPricingService {

	public static final String DEFAULT_CHAT_PROVIDER = "gemini";
	public static final String DEFAULT_CHAT_MODEL = "gemini-3.8-flash";
	public static final String PRICING_UNAVAILABLE_MESSAGE =
			"Published AI rates unavailable — cost estimate cannot be calculated right now.";

	private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");

	// Fixed token work-size budgets (tokens, not prices) used with live catalog rates for pre-step estimates
	private static final int SOURCES_EST_INPUT_TOKENS = 40_000;
	private static final int SOURCES_EST_OUTPUT_TOKENS = 3_000;

	private static final int POCKETS_EST_INPUT_TOKENS = 25_000;
	private static final int POCKETS_EST_OUTPUT_TOKENS = 4_000;

	private static final int STRUCTURE_EST_INPUT_TOKENS = 20_000;
	private static final int STRUCTURE_EST_OUTPUT_TOKENS = 2_500;

	private static final int MANUSCRIPT_SECTION_EST_INPUT_TOKENS = 12_000;
	private static final int MANUSCRIPT_SECTION_EST_OUTPUT_TOKENS = 2_500;

	private static final int BRANDING_EST_INPUT_TOKENS = 8_000;
	private static final int BRANDING_EST_OUTPUT_TOKENS = 1_000;

	private final AiModelPricingCatalog pricingCatalog;

	public boolean isPricingAvailable() {
		return pricingCatalog.getRates(DEFAULT_CHAT_PROVIDER, DEFAULT_CHAT_MODEL).isPresent();
	}

	public Optional<BigDecimal> calculateCostUsd(
			String provider,
			String model,
			int promptTokens,
			int completionTokens) {
		Optional<ModelRates> ratesOpt = pricingCatalog.getRates(provider, model);
		if (ratesOpt.isEmpty()) {
			return Optional.empty();
		}
		ModelRates rates = ratesOpt.get();
		int inTok = Math.max(0, promptTokens);
		int outTok = Math.max(0, completionTokens);

		BigDecimal inputCost = BigDecimal.valueOf(inTok).multiply(rates.inputPerMtok());
		BigDecimal outputCost = BigDecimal.valueOf(outTok).multiply(rates.outputPerMtok());
		BigDecimal usd = inputCost.add(outputCost).divide(ONE_MILLION, 6, RoundingMode.HALF_UP);
		return Optional.of(usd);
	}

	public Optional<BigDecimal> estimateSourcesUsd() {
		return estimateDefaultChatBudget(SOURCES_EST_INPUT_TOKENS, SOURCES_EST_OUTPUT_TOKENS);
	}

	public Optional<BigDecimal> estimatePocketsUsd() {
		return estimateDefaultChatBudget(POCKETS_EST_INPUT_TOKENS, POCKETS_EST_OUTPUT_TOKENS);
	}

	public Optional<BigDecimal> estimateStructureUsd() {
		return estimateDefaultChatBudget(STRUCTURE_EST_INPUT_TOKENS, STRUCTURE_EST_OUTPUT_TOKENS);
	}

	public Optional<BigDecimal> estimateManuscriptUsd(int sectionCount) {
		int count = Math.max(1, sectionCount);
		return estimateDefaultChatBudget(
				MANUSCRIPT_SECTION_EST_INPUT_TOKENS * count,
				MANUSCRIPT_SECTION_EST_OUTPUT_TOKENS * count);
	}

	public Optional<BigDecimal> estimateBrandingUsd() {
		return estimateDefaultChatBudget(BRANDING_EST_INPUT_TOKENS, BRANDING_EST_OUTPUT_TOKENS);
	}

	public Optional<BigDecimal> estimateRemainingUsd(
			RelayProduct product,
			int sourcesCount,
			int sectionCount,
			int doneSectionCount) {
		if (product == null || product.getStage() != RelayProductStage.BUILD) {
			return Optional.of(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
		}

		int cur = product.getBuildStep();
		String status = product.getStepStatus() != null ? product.getStepStatus() : "todo";
		boolean currentCompleted = "done".equals(status) || "review".equals(status);

		int totalIn = 0;
		int totalOut = 0;

		// Step 0: Sources (only if sources exist and not yet summarized)
		if (cur == 0 && !currentCompleted && sourcesCount > 0) {
			totalIn += SOURCES_EST_INPUT_TOKENS;
			totalOut += SOURCES_EST_OUTPUT_TOKENS;
		}
		// Step 1: Profitable pockets
		if (cur < 1 || (cur == 1 && !currentCompleted)) {
			totalIn += POCKETS_EST_INPUT_TOKENS;
			totalOut += POCKETS_EST_OUTPUT_TOKENS;
		}
		// Step 2: Product type ($0 AI)
		// Step 3: Structure
		if (cur < 3 || (cur == 3 && !currentCompleted)) {
			totalIn += STRUCTURE_EST_INPUT_TOKENS;
			totalOut += STRUCTURE_EST_OUTPUT_TOKENS;
		}
		// Step 4: Manuscript
		if (cur < 4) {
			int expectedSections = sectionCount > 0 ? sectionCount : 9;
			totalIn += MANUSCRIPT_SECTION_EST_INPUT_TOKENS * expectedSections;
			totalOut += MANUSCRIPT_SECTION_EST_OUTPUT_TOKENS * expectedSections;
		}
		else if (cur == 4 && !currentCompleted) {
			int remainingSections = Math.max(0, sectionCount - doneSectionCount);
			totalIn += MANUSCRIPT_SECTION_EST_INPUT_TOKENS * remainingSections;
			totalOut += MANUSCRIPT_SECTION_EST_OUTPUT_TOKENS * remainingSections;
		}
		// Step 5: Branding
		if (cur < 5 || (cur == 5 && !currentCompleted)) {
			totalIn += BRANDING_EST_INPUT_TOKENS;
			totalOut += BRANDING_EST_OUTPUT_TOKENS;
		}
		// Step 6: Ready ($0 AI)

		if (totalIn == 0 && totalOut == 0) {
			return Optional.of(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
		}

		return estimateDefaultChatBudget(totalIn, totalOut);
	}

	private Optional<BigDecimal> estimateDefaultChatBudget(int inputTokens, int outputTokens) {
		return calculateCostUsd(DEFAULT_CHAT_PROVIDER, DEFAULT_CHAT_MODEL, inputTokens, outputTokens)
				.map(usd -> usd.setScale(2, RoundingMode.HALF_UP));
	}
}
