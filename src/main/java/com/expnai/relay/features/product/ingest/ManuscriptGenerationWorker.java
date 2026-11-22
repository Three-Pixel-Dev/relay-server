package com.expnai.relay.features.product.ingest;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.expnai.relay.common.persistence.AuditUserContext;
import com.expnai.relay.domain.entity.RelayCitation;
import com.expnai.relay.domain.entity.RelayPassage;
import com.expnai.relay.domain.entity.RelayPocket;
import com.expnai.relay.domain.entity.RelayProduct;
import com.expnai.relay.domain.entity.RelaySection;
import com.expnai.relay.domain.entity.RelaySource;
import com.expnai.relay.domain.entity.RelaySourceProfile;
import com.expnai.relay.domain.entity.RelayThreadMessage;
import com.expnai.relay.domain.enums.RelaySectionStatus;
import com.expnai.relay.domain.enums.RelayThreadWho;
import com.expnai.relay.domain.repository.RelayCitationRepository;
import com.expnai.relay.domain.repository.RelayPassageRepository;
import com.expnai.relay.domain.repository.RelayPocketRepository;
import com.expnai.relay.domain.repository.RelayProductRepository;
import com.expnai.relay.domain.repository.RelaySectionRepository;
import com.expnai.relay.domain.repository.RelaySourceProfileRepository;
import com.expnai.relay.domain.repository.RelaySourceRepository;
import com.expnai.relay.domain.repository.RelayThreadMessageRepository;
import com.expnai.relay.features.ai.ManuscriptLlmService;
import com.expnai.relay.features.product.dto.response.RelayProductResponse;
import com.expnai.relay.features.product.mapper.RelayProductMapper;
import com.expnai.relay.features.product.sse.ProductSseHub;
import com.expnai.relay.features.retrieval.PassageRetrievalService;
import com.expnai.relay.features.retrieval.RetrievedPassage;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ManuscriptGenerationWorker {

	private static final Logger log = LoggerFactory.getLogger(ManuscriptGenerationWorker.class);

	private final RelayProductRepository productRepository;
	private final RelaySourceRepository sourceRepository;
	private final RelayPassageRepository passageRepository;
	private final RelaySourceProfileRepository sourceProfileRepository;
	private final RelayPocketRepository pocketRepository;
	private final RelaySectionRepository sectionRepository;
	private final RelayCitationRepository citationRepository;
	private final RelayThreadMessageRepository threadMessageRepository;
	private final PassageRetrievalService passageRetrievalService;
	private final ManuscriptLlmService manuscriptLlmService;
	private final RelayProductMapper productMapper;
	private final ProductSseHub productSseHub;
	private final TransactionTemplate transactionTemplate;
	private final ObjectMapper objectMapper;

	@Async("relayIngestionTaskExecutor")
	public void generateManuscriptAsync(Long productId, Long userId, String embedApiKey, String chatApiKey) {
		AuditUserContext.setCurrentUserId(userId);
		try {
			runPipeline(productId, embedApiKey, chatApiKey);
		}
		catch (Exception ex) {
			log.error("Manuscript generation failed for product {}: {}", productId, ex.getMessage(), ex);
			failStepGracefully(productId, "todo", ex.getMessage() != null
					? ex.getMessage()
					: "Could not write manuscript section. Please try again.");
		}
		finally {
			AuditUserContext.clear();
		}
	}

	@Async("relayIngestionTaskExecutor")
	public void regenerateSingleSectionAsync(
			Long productId,
			Long sectionId,
			String returnStatus,
			Long userId,
			String embedApiKey,
			String chatApiKey) {
		AuditUserContext.setCurrentUserId(userId);
		try {
			runSingleRegenerate(productId, sectionId, returnStatus, embedApiKey, chatApiKey);
		}
		catch (Exception ex) {
			log.error("Section {} regeneration failed for product {}: {}", sectionId, productId, ex.getMessage(), ex);
			failStepGracefully(productId, returnStatus, ex.getMessage() != null
					? ex.getMessage()
					: "Could not regenerate section. Please try again.");
		}
		finally {
			AuditUserContext.clear();
		}
	}

	private void runPipeline(Long productId, String embedApiKey, String chatApiKey) {
		ManuscriptContext ctx = loadContext(productId);
		if (ctx == null || ctx.sections().isEmpty()) {
			failStepGracefully(productId, "todo", "No sections found to write. Go back to Structure first.");
			return;
		}

		List<String> progressLines = new ArrayList<>();
		List<RelaySection> sections = ctx.sections();
		int total = sections.size();

		for (int i = 0; i < total; i++) {
			if (isProductCancelled(productId)) {
				return;
			}
			RelaySection sec = sections.get(i);
			if (sec.getStatus() == RelaySectionStatus.DONE && sec.getText() != null && !sec.getText().isBlank()) {
				continue;
			}

			addProgress(productId, progressLines,
					"Writing section " + (i + 1) + " of " + total + ": " + sec.getTitle());
			markSectionLive(sec.getId());
			publishLiveProduct(productId);

			List<RetrievedPassage> passages = retrieveSectionPassages(
					productId, ctx, sec, embedApiKey);

			if (isProductCancelled(productId)) {
				return;
			}

			String body = manuscriptLlmService.writeSection(
					chatApiKey,
					ctx.product(),
					ctx.pocket(),
					ctx.profile(),
					sec,
					i,
					total,
					sections,
					passages);

			if (isProductCancelled(productId)) {
				return;
			}

			saveCompletedSection(productId, sec.getId(), body, passages);

			// Voice gate after section 1 if voice is not yet approved
			boolean voiceApproved = Boolean.TRUE.equals(
					productRepository.findById(productId).map(RelayProduct::isVoiceApproved).orElse(false));
			if (i == 0 && !voiceApproved) {
				transactionTemplate.executeWithoutResult(status -> {
					productRepository.findById(productId).ifPresent(product -> {
						product.setStepProgress(null);
						product.setStepStatus("gate");
						productRepository.save(product);
						saveThreadMessage(product, RelayThreadWho.RELAY,
								"The first section is written. Read it. If the voice and depth are right, I'll write the rest the same way.");
					});
				});
				publishTerminalProduct(productId);
				return;
			}

			// Stream updated section to client while continuing remaining sections
			publishLiveProduct(productId);
		}

		// All sections DONE -> review
		transactionTemplate.executeWithoutResult(status -> {
			productRepository.findById(productId).ifPresent(product -> {
				int totalWords = product.getWordCount() != null ? product.getWordCount() : 0;
				product.setStepProgress(null);
				product.setStepStatus("review");
				productRepository.save(product);
				String formattedWords = NumberFormat.getIntegerInstance(Locale.US).format(totalWords);
				saveThreadMessage(product, RelayThreadWho.RELAY,
						"All " + total + " sections written, " + formattedWords
								+ " words. Regenerate any section, or click **Looks good, continue** to proceed to branding.");
			});
		});

		publishTerminalProduct(productId);
	}

	private void runSingleRegenerate(
			Long productId,
			Long sectionId,
			String returnStatus,
			String embedApiKey,
			String chatApiKey) {
		ManuscriptContext ctx = loadContext(productId);
		if (ctx == null) {
			return;
		}
		List<RelaySection> sections = ctx.sections();
		int idx = -1;
		for (int i = 0; i < sections.size(); i++) {
			if (sections.get(i).getId().equals(sectionId)) {
				idx = i;
				break;
			}
		}
		if (idx < 0) {
			failStepGracefully(productId, returnStatus, "Section not found for regeneration.");
			return;
		}

		RelaySection target = sections.get(idx);
		List<String> progressLines = new ArrayList<>();
		addProgress(productId, progressLines, "Regenerating section " + (idx + 1) + ": " + target.getTitle());
		markSectionLive(target.getId());
		publishLiveProduct(productId);

		List<RetrievedPassage> passages = retrieveSectionPassages(productId, ctx, target, embedApiKey);
		if (isProductCancelled(productId)) {
			return;
		}

		String body = manuscriptLlmService.writeSection(
				chatApiKey,
				ctx.product(),
				ctx.pocket(),
				ctx.profile(),
				target,
				idx,
				sections.size(),
				sections,
				passages);

		if (isProductCancelled(productId)) {
			return;
		}

		saveCompletedSection(productId, target.getId(), body, passages);

		String finalStatus = ("gate".equals(returnStatus) || "review".equals(returnStatus))
				? returnStatus
				: "review";

		transactionTemplate.executeWithoutResult(status -> {
			productRepository.findById(productId).ifPresent(product -> {
				product.setStepProgress(null);
				product.setStepStatus(finalStatus);
				productRepository.save(product);
				saveThreadMessage(product, RelayThreadWho.RELAY,
						"Regenerated **" + target.getTitle() + "**.");
			});
		});

		publishTerminalProduct(productId);
	}

	private ManuscriptContext loadContext(Long productId) {
		return transactionTemplate.execute(status -> {
			RelayProduct product = productRepository.findById(productId).orElse(null);
			if (product == null || product.isCancelled()) {
				return null;
			}
			RelayPocket chosenPocket = product.getChosenPocket() != null
					? pocketRepository.findById(product.getChosenPocket().getId()).orElse(null)
					: null;
			RelaySourceProfile profile = sourceProfileRepository.findByProductId(productId).orElse(null);
			List<RelaySection> sections = sectionRepository.findByProductIdOrderBySortOrderAscIdAsc(productId);
			long passageCount = passageRepository.countByProductId(productId);
			return new ManuscriptContext(product, chosenPocket, profile, sections, passageCount);
		});
	}

	private record ManuscriptContext(
			RelayProduct product,
			RelayPocket pocket,
			RelaySourceProfile profile,
			List<RelaySection> sections,
			long passageCount
	) {
	}

	private List<RetrievedPassage> retrieveSectionPassages(
			Long productId,
			ManuscriptContext ctx,
			RelaySection section,
			String embedApiKey) {
		if (ctx.passageCount() <= 0 || embedApiKey == null || embedApiKey.isBlank()) {
			return List.of();
		}
		try {
			StringBuilder q = new StringBuilder();
			if (section.getTitle() != null) {
				q.append(section.getTitle()).append(' ');
			}
			if (section.getMoment() != null) {
				q.append(section.getMoment()).append(' ');
			}
			if (ctx.pocket() != null && ctx.pocket().getNiche() != null) {
				q.append(ctx.pocket().getNiche());
			}
			return passageRetrievalService.retrieveWithApiKey(productId, q.toString().trim(), 8, embedApiKey, 4);
		}
		catch (Exception ex) {
			log.warn("RAG retrieval skipped for section {} on product {}: {}",
					section.getId(), productId, ex.getMessage());
			return List.of();
		}
	}

	private void markSectionLive(Long sectionId) {
		transactionTemplate.executeWithoutResult(status -> {
			sectionRepository.findById(sectionId).ifPresent(sec -> {
				sec.setStatus(RelaySectionStatus.LIVE);
				sectionRepository.save(sec);
			});
		});
	}

	private void saveCompletedSection(
			Long productId,
			Long sectionId,
			String body,
			List<RetrievedPassage> passages) {
		int wordCount = countWords(body);
		transactionTemplate.executeWithoutResult(status -> {
			RelaySection sec = sectionRepository.findById(sectionId).orElse(null);
			if (sec == null) {
				return;
			}
			sec.setText(body);
			sec.setWords(wordCount);
			sec.setStatus(RelaySectionStatus.DONE);
			sec.setVersion(sec.getVersion() + 1);
			sectionRepository.save(sec);

			citationRepository.deleteHardBySectionId(sectionId);
			if (passages != null && !passages.isEmpty()) {
				Set<String> seenLocators = new LinkedHashSet<>();
				for (RetrievedPassage p : passages) {
					String label = (p.locator() != null && !p.locator().isBlank())
							? p.locator().trim()
							: (p.sourceName() != null ? p.sourceName().trim() : "");
					if (label.isEmpty() || !seenLocators.add(label)) {
						continue;
					}
					RelayCitation citation = new RelayCitation();
					citation.setSection(sec);
					if (p.passageId() != null) {
						RelayPassage passageRef = passageRepository.getReferenceById(p.passageId());
						citation.setPassage(passageRef);
					}
					citation.setLocator(label);
					citationRepository.save(citation);
					if (seenLocators.size() >= 4) {
						break;
					}
				}
			}

			int totalProductWords = sectionRepository.findByProductIdOrderBySortOrderAscIdAsc(productId)
					.stream()
					.mapToInt(s -> s.getWords() != null ? s.getWords() : 0)
					.sum();

			productRepository.findById(productId).ifPresent(product -> {
				product.setWordCount(totalProductWords);
				productRepository.save(product);
			});
		});
	}

	private int countWords(String text) {
		if (text == null || text.isBlank()) {
			return 0;
		}
		return text.trim().split("\\s+").length;
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

	private void failStepGracefully(Long productId, String fallbackStatus, String message) {
		String targetStatus = (fallbackStatus != null && !fallbackStatus.isBlank()) ? fallbackStatus : "todo";
		transactionTemplate.executeWithoutResult(status -> {
			productRepository.findById(productId).ifPresent(product -> {
				product.setStepProgress(null);
				product.setStepStatus(targetStatus);
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

	private void publishLiveProduct(Long productId) {
		RelayProductResponse dto = buildProductResponse(productId);
		if (dto != null) {
			productSseHub.publish(productId, "product", dto, false);
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
			List<RelayCitation> citations = sections.isEmpty()
					? List.of()
					: citationRepository.findBySectionIdInOrderBySectionIdAscIdAsc(
							sections.stream().map(RelaySection::getId).toList());
			List<RelayThreadMessage> messages = threadMessageRepository
					.findByProductIdOrderByCreatedAtAscIdAsc(productId);
			return productMapper.toDto(product, sources, profile, pockets, sections, citations, messages);
		});
	}
}

