package com.expnai.relay.features.product.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.expnai.relay.common.logging.RelayTrace;
import com.expnai.relay.common.persistence.AuditUserContext;
import com.expnai.relay.domain.entity.RelayBrand;
import com.expnai.relay.domain.entity.RelayCitation;
import com.expnai.relay.domain.entity.RelayPocket;
import com.expnai.relay.domain.entity.RelayProduct;
import com.expnai.relay.domain.entity.RelayProductFile;
import com.expnai.relay.domain.entity.RelaySection;
import com.expnai.relay.domain.entity.RelaySource;
import com.expnai.relay.domain.entity.RelaySourceProfile;
import com.expnai.relay.domain.entity.RelayThreadMessage;
import com.expnai.relay.domain.enums.RelayThreadWho;
import com.expnai.relay.domain.repository.RelayBrandRepository;
import com.expnai.relay.domain.repository.RelayCitationRepository;
import com.expnai.relay.domain.repository.RelayPocketRepository;
import com.expnai.relay.domain.repository.RelayProductFileRepository;
import com.expnai.relay.domain.repository.RelayProductRepository;
import com.expnai.relay.domain.repository.RelaySectionRepository;
import com.expnai.relay.domain.repository.RelaySourceProfileRepository;
import com.expnai.relay.domain.repository.RelaySourceRepository;
import com.expnai.relay.domain.repository.RelayThreadMessageRepository;
import com.expnai.relay.features.ai.ProductTypePriceSuggestionService;
import com.expnai.relay.features.ai.ProductTypePriceSuggestionService.TypePriceSuggestion;
import com.expnai.relay.features.product.dto.response.RelayProductResponse;
import com.expnai.relay.features.product.mapper.RelayProductMapper;
import com.expnai.relay.features.product.sse.ProductSseHub;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProductTypePriceWorker {

	private static final Logger log = LoggerFactory.getLogger(ProductTypePriceWorker.class);
	private static final String SHAPE_PROMPT_MSG =
			"What shape should it take? Planners and workbooks sell most on Etsy; a guide teaches best; a checklist makes a strong lead magnet.";

	private final RelayProductRepository productRepository;
	private final RelaySourceRepository sourceRepository;
	private final RelaySourceProfileRepository sourceProfileRepository;
	private final RelayPocketRepository pocketRepository;
	private final RelaySectionRepository sectionRepository;
	private final RelayCitationRepository citationRepository;
	private final RelayBrandRepository brandRepository;
	private final RelayProductFileRepository productFileRepository;
	private final RelayThreadMessageRepository threadMessageRepository;
	private final ProductTypePriceSuggestionService priceSuggestionService;
	private final RelayProductMapper productMapper;
	private final ProductSseHub productSseHub;
	private final TransactionTemplate transactionTemplate;
	private final ObjectMapper objectMapper;

	@Async("relayIngestionTaskExecutor")
	public void suggestPricesAsync(Long productId, Long userId, String chatApiKey) {
		AuditUserContext.setCurrentUserId(userId);
		RelayTrace.step("start product={} step=2(ProductTypePrices)", productId);
		try {
			runPipeline(productId, chatApiKey);
			RelayTrace.step("done product={} step=2(ProductTypePrices)", productId);
		}
		catch (Exception ex) {
			log.warn("Product type price suggestion failed for product {} (falling back to catalog prices): {}",
					productId, ex.getMessage());
			finishReview(productId, Map.of());
		}
		finally {
			AuditUserContext.clear();
		}
	}

	private void runPipeline(Long productId, String chatApiKey) {
		TypeContext ctx = transactionTemplate.execute(status -> {
			RelayProduct product = productRepository.findById(productId).orElse(null);
			if (product == null || product.isCancelled()) {
				return null;
			}
			RelayPocket pocket = product.getChosenPocket() != null
					? pocketRepository.findById(product.getChosenPocket().getId()).orElse(null)
					: null;
			return new TypeContext(product, pocket);
		});
		if (ctx == null) {
			return;
		}

		List<String> progressLines = new ArrayList<>();
		addProgress(productId, progressLines, "Checking live marketplace prices for each format");
		addProgress(productId, progressLines, "Suggesting buyer price ranges");

		Map<String, TypePriceSuggestion> suggestions = priceSuggestionService.suggestPrices(
				chatApiKey, ctx.product(), ctx.pocket());

		if (isProductCancelled(productId)) {
			return;
		}

		finishReview(productId, suggestions);
	}

	private void finishReview(Long productId, Map<String, TypePriceSuggestion> suggestions) {
		transactionTemplate.executeWithoutResult(status -> {
			RelayProduct product = productRepository.findById(productId).orElse(null);
			if (product == null) {
				return;
			}
			if (suggestions != null && !suggestions.isEmpty()) {
				try {
					product.setTypePriceSuggestionsJson(objectMapper.writeValueAsString(suggestions));
				}
				catch (Exception ignored) {
				}
			}
			product.setStepProgress(null);
			product.setStepStatus("review");
			productRepository.save(product);

			if (product.getProductType() == null) {
				saveThreadMessage(product, RelayThreadWho.RELAY, SHAPE_PROMPT_MSG);
			}
		});
		publishTerminalProduct(productId);
	}

	private record TypeContext(RelayProduct product, RelayPocket pocket) {
	}

	private boolean isProductCancelled(Long productId) {
		return productRepository.findById(productId)
				.map(RelayProduct::isCancelled)
				.orElse(true);
	}

	private void addProgress(Long productId, List<String> progressLines, String newLine) {
		progressLines.add(newLine);
		transactionTemplate.executeWithoutResult(status -> {
			productRepository.findById(productId).ifPresent(product -> {
				try {
					product.setStepProgress(objectMapper.writeValueAsString(progressLines));
					productRepository.save(product);
				}
				catch (Exception ignored) {
				}
			});
		});
		productSseHub.publish(
				productId,
				"progress",
				Map.of("progress", List.copyOf(progressLines), "stepStatus", "running"),
				false);
	}

	private void saveThreadMessage(RelayProduct product, RelayThreadWho who, String text) {
		RelayThreadMessage msg = new RelayThreadMessage();
		msg.setProduct(product);
		msg.setWho(who);
		msg.setText(text);
		threadMessageRepository.save(msg);
	}

	private void publishTerminalProduct(Long productId) {
		RelayProductResponse dto = buildProductResponse(productId);
		if (dto != null) {
			productSseHub.publish(productId, "product", dto, true);
		}
	}

	private RelayProductResponse buildProductResponse(Long productId) {
		return transactionTemplate.execute(status -> {
			RelayProduct product = productRepository.findById(productId).orElse(null);
			if (product == null) {
				return null;
			}
			List<RelaySource> sources = sourceRepository.findByProductIdOrderByCreatedAtAsc(productId);
			RelaySourceProfile profile = sourceProfileRepository.findByProductId(productId).orElse(null);
			List<RelayPocket> pockets = pocketRepository.findByProductIdOrderByIdAsc(productId);
			List<RelaySection> sections = sectionRepository.findByProductIdOrderBySortOrderAscIdAsc(productId);
			List<RelayCitation> citations = sections.isEmpty()
					? List.of()
					: citationRepository.findBySectionIdInOrderBySectionIdAscIdAsc(
							sections.stream().map(RelaySection::getId).toList());
			RelayBrand brand = brandRepository.findByProductId(productId).orElse(null);
			List<RelayProductFile> files = productFileRepository.findByProductIdOrderByIdAsc(productId);
			List<RelayThreadMessage> messages = threadMessageRepository
					.findByProductIdOrderByCreatedAtAscIdAsc(productId);
			return productMapper.toDto(product, sources, profile, pockets, sections, citations, brand, files, messages);
		});
	}
}

