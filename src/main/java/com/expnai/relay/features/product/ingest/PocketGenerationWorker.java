package com.expnai.relay.features.product.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.expnai.relay.common.persistence.AuditUserContext;
import com.expnai.relay.domain.entity.RelayPocket;
import com.expnai.relay.domain.entity.RelayProduct;
import com.expnai.relay.domain.entity.RelaySection;
import com.expnai.relay.domain.entity.RelaySource;
import com.expnai.relay.domain.entity.RelaySourceProfile;
import com.expnai.relay.domain.entity.RelayThreadMessage;
import com.expnai.relay.domain.enums.RelayThreadWho;
import com.expnai.relay.domain.repository.RelayPocketRepository;
import com.expnai.relay.domain.repository.RelayProductRepository;
import com.expnai.relay.domain.repository.RelaySectionRepository;
import com.expnai.relay.domain.repository.RelaySourceProfileRepository;
import com.expnai.relay.domain.repository.RelaySourceRepository;
import com.expnai.relay.domain.repository.RelayThreadMessageRepository;
import com.expnai.relay.features.ai.PocketLlmService;
import com.expnai.relay.features.ai.PocketLlmService.GeneratedPocket;
import com.expnai.relay.features.market.MarketResearchPack;
import com.expnai.relay.features.market.MarketResearchService;
import com.expnai.relay.features.product.dto.response.RelayProductResponse;
import com.expnai.relay.features.product.mapper.RelayProductMapper;
import com.expnai.relay.features.product.sse.ProductSseHub;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PocketGenerationWorker {

	private static final Logger log = LoggerFactory.getLogger(PocketGenerationWorker.class);

	private final RelayProductRepository productRepository;
	private final RelaySourceRepository sourceRepository;
	private final RelaySourceProfileRepository sourceProfileRepository;
	private final RelayPocketRepository pocketRepository;
	private final RelaySectionRepository sectionRepository;
	private final RelayThreadMessageRepository threadMessageRepository;
	private final MarketResearchService marketResearchService;
	private final PocketLlmService pocketLlmService;
	private final RelayProductMapper productMapper;
	private final ProductSseHub productSseHub;
	private final TransactionTemplate transactionTemplate;
	private final ObjectMapper objectMapper;

	@Async("relayIngestionTaskExecutor")
	public void generatePocketsAsync(Long productId, Long userId, String chatApiKey) {
		AuditUserContext.setCurrentUserId(userId);
		try {
			runPipeline(productId, chatApiKey);
		}
		catch (Exception ex) {
			log.error("Pocket generation failed for product {}: {}", productId, ex.getMessage(), ex);
			failStepGracefully(productId, ex.getMessage() != null
					? ex.getMessage()
					: "Could not generate profitable pockets. Please try again.");
		}
		finally {
			AuditUserContext.clear();
		}
	}

	private void runPipeline(Long productId, String chatApiKey) {
		RelayProduct snapshot = productRepository.findById(productId).orElse(null);
		if (snapshot == null || snapshot.isCancelled()) {
			return;
		}
		RelaySourceProfile profile = sourceProfileRepository.findByProductId(productId).orElse(null);
		List<String> progressLines = new ArrayList<>();

		addProgress(productId, progressLines, "Deriving buyer search keywords");
		List<String> keywords = pocketLlmService.deriveKeywords(chatApiKey, snapshot, profile);

		if (isProductCancelled(productId)) {
			return;
		}

		MarketResearchPack pack = marketResearchService.research(
				keywords,
				line -> addProgress(productId, progressLines, line));

		if (isProductCancelled(productId)) {
			return;
		}

		addProgress(productId, progressLines, "Scoring pockets");
		List<GeneratedPocket> generatedPockets = pocketLlmService.generatePockets(
				chatApiKey,
				snapshot,
				profile,
				pack);

		if (isProductCancelled(productId)) {
			return;
		}

		transactionTemplate.executeWithoutResult(status -> {
			RelayProduct product = productRepository.findById(productId).orElse(null);
			if (product == null) {
				return;
			}
			product.setChosenPocket(null);
			productRepository.saveAndFlush(product);
			pocketRepository.deleteHardByProductId(productId);

			for (GeneratedPocket gp : generatedPockets) {
				RelayPocket entity = new RelayPocket();
				entity.setProduct(product);
				entity.setHeadline(gp.h());
				entity.setIndustry(gp.ind());
				entity.setNiche(gp.niche());
				entity.setKnowledge(gp.know());
				entity.setProblem(gp.prob());
				try {
					entity.setScoresJson(objectMapper.writeValueAsString(gp.scores()));
				}
				catch (Exception ex) {
					entity.setScoresJson("{\"pain\":75,\"worse\":72,\"power\":74,\"speed\":78}");
				}
				try {
					entity.setEvidenceJson(objectMapper.writeValueAsString(gp.src()));
				}
				catch (Exception ex) {
					entity.setEvidenceJson("[]");
				}
				entity.setRecommended(gp.rec());
				entity.setGrounded(gp.grounded());
				pocketRepository.save(entity);
			}

			try {
				Map<String, Object> persistedPack = Map.of(
						"queriedAt", pack.queriedAt(),
						"keywords", pack.keywords(),
						"etsyResults", pack.etsyResults());
				product.setMarketResearchJson(objectMapper.writeValueAsString(persistedPack));
			}
			catch (Exception ignored) {
			}
			product.setTypePriceSuggestionsJson(null);
			product.setStepProgress(null);
			product.setStepStatus("review");
			productRepository.save(product);

			String msg = "Found " + generatedPockets.size()
					+ " profitable pockets backed by live market data. Pick the one you want to build, or suggest a change to regenerate.";
			saveThreadMessage(product, RelayThreadWho.RELAY, msg);
		});

		publishTerminalProduct(productId);
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

	private void failStepGracefully(Long productId, String message) {
		transactionTemplate.executeWithoutResult(status -> {
			productRepository.findById(productId).ifPresent(product -> {
				product.setStepProgress(null);
				product.setStepStatus("todo");
				productRepository.save(product);
				saveThreadMessage(product, RelayThreadWho.RELAY, message);
			});
		});
		RelayProductResponse dto = buildProductResponse(productId);
		if (dto != null) {
			productSseHub.publish(productId, "product", dto, true);
		}
		else {
			productSseHub.publish(productId, "error", Map.of("message", message), true);
		}
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
			List<RelayThreadMessage> messages = threadMessageRepository
					.findByProductIdOrderByCreatedAtAscIdAsc(productId);
			return productMapper.toDto(product, sources, profile, pockets, sections, messages);
		});
	}
}

