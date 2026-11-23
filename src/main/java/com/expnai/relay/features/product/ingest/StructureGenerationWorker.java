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
import com.expnai.relay.domain.entity.RelayPocket;
import com.expnai.relay.domain.entity.RelayProduct;
import com.expnai.relay.domain.entity.RelaySection;
import com.expnai.relay.domain.entity.RelaySource;
import com.expnai.relay.domain.entity.RelaySourceProfile;
import com.expnai.relay.domain.entity.RelayThreadMessage;
import com.expnai.relay.domain.enums.RelaySectionStatus;
import com.expnai.relay.domain.enums.RelayThreadWho;
import com.expnai.relay.domain.repository.RelayPassageRepository;
import com.expnai.relay.domain.repository.RelayPocketRepository;
import com.expnai.relay.domain.repository.RelayProductRepository;
import com.expnai.relay.domain.repository.RelaySectionRepository;
import com.expnai.relay.domain.repository.RelaySourceProfileRepository;
import com.expnai.relay.domain.repository.RelaySourceRepository;
import com.expnai.relay.domain.repository.RelayThreadMessageRepository;
import com.expnai.relay.features.ai.StructureLlmService;
import com.expnai.relay.features.ai.StructureLlmService.GeneratedSection;
import com.expnai.relay.features.product.dto.response.RelayProductResponse;
import com.expnai.relay.features.product.mapper.RelayProductMapper;
import com.expnai.relay.features.product.sse.ProductSseHub;
import com.expnai.relay.features.retrieval.PassageRetrievalService;
import com.expnai.relay.features.retrieval.RetrievedPassage;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StructureGenerationWorker {

	private static final Logger log = LoggerFactory.getLogger(StructureGenerationWorker.class);

	private final RelayProductRepository productRepository;
	private final RelaySourceRepository sourceRepository;
	private final RelayPassageRepository passageRepository;
	private final RelaySourceProfileRepository sourceProfileRepository;
	private final RelayPocketRepository pocketRepository;
	private final RelaySectionRepository sectionRepository;
	private final RelayThreadMessageRepository threadMessageRepository;
	private final PassageRetrievalService passageRetrievalService;
	private final StructureLlmService structureLlmService;
	private final RelayProductMapper productMapper;
	private final ProductSseHub productSseHub;
	private final TransactionTemplate transactionTemplate;
	private final ObjectMapper objectMapper;

	@Async("relayIngestionTaskExecutor")
	public void generateStructureAsync(Long productId, Long userId, String embedApiKey, String chatApiKey) {
		AuditUserContext.setCurrentUserId(userId);
		RelayTrace.step("start product={} step=3(Structure)", productId);
		try {
			runPipeline(productId, embedApiKey, chatApiKey);
			RelayTrace.step("done product={} step=3(Structure)", productId);
		}
		catch (Exception ex) {
			log.error("Structure generation failed for product {}: {}", productId, ex.getMessage(), ex);
			failStepGracefully(productId, ex.getMessage() != null
					? ex.getMessage()
					: "Could not generate structure. Please try again.");
		}
		finally {
			AuditUserContext.clear();
		}
	}

	private void runPipeline(Long productId, String embedApiKey, String chatApiKey) {
		StructureContext ctx = transactionTemplate.execute(status -> {
			RelayProduct product = productRepository.findById(productId).orElse(null);
			if (product == null || product.isCancelled()) {
				return null;
			}
			RelayPocket chosenPocket = null;
			if (product.getChosenPocket() != null) {
				chosenPocket = pocketRepository.findById(product.getChosenPocket().getId()).orElse(null);
			}
			RelaySourceProfile profile = sourceProfileRepository.findByProductId(productId).orElse(null);
			long passageCount = passageRepository.countByProductId(productId);
			return new StructureContext(product, chosenPocket, profile, passageCount);
		});

		if (ctx == null) {
			return;
		}

		List<String> progressLines = new ArrayList<>();
		addProgress(productId, progressLines, "Mapping the method into sections");

		List<RetrievedPassage> snippets = List.of();
		if (ctx.passageCount() > 0 && embedApiKey != null && !embedApiKey.isBlank()) {
			try {
				String query = buildRetrievalQuery(ctx.product(), ctx.pocket(), ctx.profile());
				snippets = passageRetrievalService.retrieveWithApiKey(productId, query, 12, embedApiKey, 3);
			}
			catch (Exception ex) {
				log.warn("RAG retrieval skipped during structure generation for product {}: {}",
						productId, ex.getMessage());
			}
		}

		if (isProductCancelled(productId)) {
			return;
		}

		addProgress(productId, progressLines, "Naming the moment each section owns");
		List<GeneratedSection> generatedSections = structureLlmService.generateStructure(
				chatApiKey,
				ctx.product(),
				ctx.pocket(),
				ctx.profile(),
				snippets);

		if (isProductCancelled(productId)) {
			return;
		}

		addProgress(productId, progressLines, "Adding the fill-in pages");

		transactionTemplate.executeWithoutResult(status -> {
			RelayProduct product = productRepository.findById(productId).orElse(null);
			if (product == null) {
				return;
			}
			sectionRepository.deleteHardByProductId(productId);

			int order = 0;
			for (GeneratedSection gs : generatedSections) {
				RelaySection entity = new RelaySection();
				entity.setProduct(product);
				entity.setTitle(gs.title());
				entity.setMoment(gs.moment());
				entity.setText("");
				entity.setWords(0);
				entity.setStatus(RelaySectionStatus.TODO);
				entity.setVersion(1);
				entity.setSortOrder(order++);
				sectionRepository.save(entity);
			}

			product.setStepProgress(null);
			product.setStepStatus("review");
			productRepository.save(product);

			String msg = generatedSections.size()
					+ " sections. Each one owns a moment the reader actually faces. Remove any, add your own, then click **Looks good, continue**.";
			saveThreadMessage(product, RelayThreadWho.RELAY, msg);
		});

		publishTerminalProduct(productId);
	}

	private record StructureContext(
			RelayProduct product,
			RelayPocket pocket,
			RelaySourceProfile profile,
			long passageCount
	) {
	}

	private String buildRetrievalQuery(RelayProduct product, RelayPocket pocket, RelaySourceProfile profile) {
		StringBuilder sb = new StringBuilder();
		if (pocket != null) {
			if (pocket.getNiche() != null && !pocket.getNiche().isBlank()) {
				sb.append(pocket.getNiche().trim()).append(' ');
			}
			if (pocket.getProblem() != null && !pocket.getProblem().isBlank()) {
				sb.append(pocket.getProblem().trim()).append(' ');
			}
		}
		if (profile != null && profile.getNiche() != null && !profile.getNiche().isBlank()) {
			sb.append(profile.getNiche().trim()).append(' ');
		}
		if (product != null && product.getStepNote() != null && !product.getStepNote().isBlank()) {
			sb.append(product.getStepNote().trim()).append(' ');
		}
		sb.append("steps exercises method examples chapters");
		return sb.toString().trim();
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

