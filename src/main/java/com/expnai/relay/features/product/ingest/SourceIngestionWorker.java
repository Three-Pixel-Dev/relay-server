package com.expnai.relay.features.product.ingest;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.expnai.relay.common.persistence.AuditUserContext;
import com.expnai.relay.domain.entity.RelayProduct;
import com.expnai.relay.domain.entity.RelaySource;
import com.expnai.relay.domain.entity.RelaySourceProfile;
import com.expnai.relay.domain.entity.RelayThreadMessage;
import com.expnai.relay.domain.enums.RelaySourceKind;
import com.expnai.relay.domain.enums.RelaySourceStatus;
import com.expnai.relay.domain.enums.RelayThreadWho;
import com.expnai.relay.domain.repository.RelayPassageRepository;
import com.expnai.relay.domain.repository.RelayProductRepository;
import com.expnai.relay.domain.repository.RelaySourceProfileRepository;
import com.expnai.relay.domain.repository.RelaySourceRepository;
import com.expnai.relay.domain.repository.RelayThreadMessageRepository;
import com.expnai.relay.features.ai.GeminiEmbeddingService;
import com.expnai.relay.features.ai.SourceSummaryLlmService;
import com.expnai.relay.features.ai.SourceSummaryLlmService.GeneratedSourceProfile;
import com.expnai.relay.features.product.ingest.SourceParserService.ParseResult;
import com.expnai.relay.features.product.ingest.SourceParserService.PassageChunk;
import com.expnai.relay.features.retrieval.PassageRetrievalService;
import com.expnai.relay.features.retrieval.RetrievedPassage;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SourceIngestionWorker {

	private static final Logger log = LoggerFactory.getLogger(SourceIngestionWorker.class);
	private static final BigDecimal STEP_0_SPEND = new BigDecimal("0.25");

	private final RelayProductRepository productRepository;
	private final RelaySourceRepository sourceRepository;
	private final RelayPassageRepository passageRepository;
	private final RelaySourceProfileRepository sourceProfileRepository;
	private final RelayThreadMessageRepository threadMessageRepository;
	private final SourceParserService sourceParserService;
	private final GeminiEmbeddingService geminiEmbeddingService;
	private final PassageRetrievalService passageRetrievalService;
	private final SourceSummaryLlmService sourceSummaryLlmService;
	private final TransactionTemplate transactionTemplate;
	private final ObjectMapper objectMapper;

	@Async("relayIngestionTaskExecutor")
	public void ingestAndSummarizeAsync(
			Long productId,
			Long userId,
			String embedApiKey,
			String chatApiKey) {
		AuditUserContext.setCurrentUserId(userId);
		try {
			runPipeline(productId, userId, embedApiKey, chatApiKey);
		}
		catch (Exception ex) {
			log.error("Unexpected failure in ingestion pipeline for product {}: {}", productId, ex.getMessage(), ex);
			failStepGracefully(productId, "Source reading encountered an unexpected error: " + ex.getMessage());
		}
		finally {
			AuditUserContext.clear();
		}
	}

	private void runPipeline(Long productId, Long userId, String embedApiKey, String chatApiKey) {
		List<RelaySource> sources = sourceRepository.findByProductIdOrderByCreatedAtAsc(productId);
		List<String> progressLines = new ArrayList<>();

		long ytCount = sources.stream().filter(s -> s.getKind() == RelaySourceKind.YOUTUBE).count();
		long fileCount = sources.stream().filter(s -> s.getKind() == RelaySourceKind.FILE).count();
		long webCount = sources.stream().filter(s -> s.getKind() == RelaySourceKind.WEB).count();
		long igCount = sources.stream().filter(s -> s.getKind() == RelaySourceKind.INSTAGRAM).count();

		if (ytCount > 0) {
			addProgress(productId, progressLines, "YouTube: reading the last 30 videos and their captions");
		}
		if (fileCount > 0) {
			addProgress(productId, progressLines,
					"Reading " + fileCount + " document" + (fileCount > 1 ? "s" : "") + " and splitting into passages");
		}
		if (webCount > 0) {
			addProgress(productId, progressLines, "Reading the linked page");
		}

		// Handle Instagram sources first (marked SKIPPED with clear terminal status + thread note)
		for (RelaySource source : sources) {
			if (source.getKind() == RelaySourceKind.INSTAGRAM) {
				String skipReason = "Instagram reading requires creator OAuth approval (out of scope for Phase 1).";
				markSourceSkipped(source.getId(), productId, skipReason);
				appendThreadMessage(productId, RelayThreadWho.RELAY,
						"Skipped Instagram source (**" + source.getName() + "**) — " + skipReason);
			}
		}

		int totalVideosRead = 0;
		int totalDocsRead = 0;
		boolean addedIndexingProgress = false;

		for (RelaySource source : sources) {
			if (isProductCancelled(productId)) {
				return;
			}
			if (source.getKind() == RelaySourceKind.INSTAGRAM) {
				continue;
			}

			markSourceReading(source.getId(), productId);
			try {
				ParseResult parsed = sourceParserService.parseSource(source);
				if (!addedIndexingProgress) {
					addProgress(productId, progressLines, "Indexing passages so every section can cite them");
					addedIndexingProgress = true;
				}
				List<EmbeddedChunk> embeddedChunks = new ArrayList<>(parsed.chunks().size());
				for (PassageChunk chunk : parsed.chunks()) {
					if (isProductCancelled(productId)) {
						return;
					}
					String vectorLiteral = geminiEmbeddingService.embedDocumentAsVectorLiteral(
							embedApiKey,
							source.getName(),
							chunk.text());
					embeddedChunks.add(new EmbeddedChunk(chunk.text(), chunk.locator(), vectorLiteral));
				}

				if (isProductCancelled(productId)) {
					return;
				}

				// Insert all passages for this source only after all embeddings succeed
				transactionTemplate.executeWithoutResult(status -> {
					for (EmbeddedChunk ec : embeddedChunks) {
						passageRetrievalService.insertPassage(
								productId,
								source.getId(),
								userId,
								ec.text(),
								ec.locator(),
								ec.vectorLiteral());
					}
				});

				totalVideosRead += parsed.videosRead();
				totalDocsRead += parsed.docsRead();
				markSourceReady(source.getId());
			}
			catch (Exception sourceEx) {
				log.warn("Source {} ({}) failed during ingestion: {}",
						source.getId(), source.getName(), sourceEx.getMessage());
				markSourceFailed(source.getId(), productId, sourceEx.getMessage());
				appendThreadMessage(productId, RelayThreadWho.RELAY,
						"Could not read **" + source.getName() + "**: " + sourceEx.getMessage());
			}
		}

		if (isProductCancelled(productId)) {
			return;
		}

		long totalPassages = passageRepository.countByProductId(productId);
		if (totalPassages == 0) {
			if (igCount > 0 && ytCount == 0 && fileCount == 0 && webCount == 0) {
				transactionTemplate.executeWithoutResult(status -> {
					productRepository.findById(productId).ifPresent(product -> {
						product.setStepProgress(null);
						product.setStepStatus("done");
						product.setBuildStep(1);
						productRepository.save(product);
						saveThreadMessage(product, RelayThreadWho.RELAY,
								"Next: **Profitable pockets**. About $0.30 in AI, a minute.");
					});
				});
			}
			else {
				failStepGracefully(productId,
						"No passages could be indexed from the sources. Check the error above, replace or remove the failed source, and press Proceed again.");
			}
			return;
		}

		addProgress(productId, progressLines, "Summarising niche, themes, voice and audience");

		RelayProduct snapshot = productRepository.findById(productId).orElse(null);
		if (snapshot == null || snapshot.isCancelled()) {
			return;
		}

		String queryText = buildSummarySearchQuery(snapshot, sources);
		List<RetrievedPassage> topPassages = passageRetrievalService.retrieveWithApiKey(
				productId,
				queryText,
				25,
				embedApiKey);

		GeneratedSourceProfile generated = sourceSummaryLlmService.summarizePassages(
				chatApiKey,
				snapshot,
				topPassages);

		final int finalVideos = totalVideosRead;
		final int finalDocs = totalDocsRead;
		final int finalPassages = (int) totalPassages;

		transactionTemplate.executeWithoutResult(status -> {
			RelayProduct product = productRepository.findById(productId).orElse(null);
			if (product == null) {
				return;
			}
			sourceProfileRepository.deleteHardByProductId(productId);
			RelaySourceProfile profile = new RelaySourceProfile();
			profile.setProduct(product);
			profile.setNiche(generated.niche());
			profile.setTransformation(generated.transformation());
			try {
				profile.setThemesJson(objectMapper.writeValueAsString(generated.themes()));
			}
			catch (Exception ex) {
				profile.setThemesJson("[]");
			}
			profile.setVerdict(generated.verdict());
			profile.setAudience(generated.audience());
			profile.setVoice(generated.voice());
			profile.setVideosCount(finalVideos);
			profile.setDocsCount(finalDocs);
			profile.setPassagesCount(finalPassages);
			sourceProfileRepository.save(profile);

			if (product.getInput() == null || product.getInput().isBlank()) {
				product.setInput(generated.niche());
			}
			product.setStepProgress(null);
			product.setStepStatus("review");
			BigDecimal currentSpend = product.getSpend() != null ? product.getSpend() : BigDecimal.ZERO;
			product.setSpend(currentSpend.add(STEP_0_SPEND));
			productRepository.save(product);

			String summaryMsg = buildSummaryReadyMessage(finalVideos, finalDocs, finalPassages);
			saveThreadMessage(product, RelayThreadWho.RELAY, summaryMsg);
		});
	}

	private String buildSummarySearchQuery(RelayProduct product, List<RelaySource> sources) {
		StringBuilder sb = new StringBuilder();
		if (product.getInput() != null && !product.getInput().isBlank()) {
			sb.append(product.getInput().trim()).append(' ');
		}
		if (product.getStepNote() != null && !product.getStepNote().isBlank()) {
			sb.append(product.getStepNote().trim()).append(' ');
		}
		for (RelaySource s : sources) {
			if (s.getName() != null && !s.getName().isBlank()) {
				sb.append(s.getName()).append(' ');
			}
		}
		sb.append("core method niche transformation themes audience voice");
		return sb.toString().trim();
	}

	private String buildSummaryReadyMessage(int videos, int docs, int passages) {
		StringBuilder readPart = new StringBuilder();
		if (videos > 0) {
			readPart.append(videos).append(" video").append(videos > 1 ? "s" : "");
		}
		if (videos > 0 && docs > 0) {
			readPart.append(" and ");
		}
		if (docs > 0) {
			readPart.append(docs).append(" document").append(docs > 1 ? "s" : "");
		}
		if (readPart.length() == 0) {
			readPart.append("sources");
		}
		String formattedPassages = NumberFormat.getIntegerInstance(Locale.US).format(passages);
		return "Read " + readPart + "; " + formattedPassages
				+ " passages indexed. Here is what the sources say. If it matches, I use it to find the pockets and to write in this voice. Uploaded documents take priority over what I infer.";
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
	}

	private void markSourceReading(Long sourceId, Long productId) {
		transactionTemplate.executeWithoutResult(status -> {
			passageRepository.deleteHardBySourceIdAndProductId(sourceId, productId);
			sourceRepository.findById(sourceId).ifPresent(source -> {
				source.setStatus(RelaySourceStatus.READING);
				source.setErrorMessage(null);
				sourceRepository.save(source);
			});
		});
	}

	private void markSourceReady(Long sourceId) {
		transactionTemplate.executeWithoutResult(status -> {
			sourceRepository.findById(sourceId).ifPresent(source -> {
				source.setStatus(RelaySourceStatus.READY);
				source.setErrorMessage(null);
				sourceRepository.save(source);
			});
		});
	}

	private void markSourceSkipped(Long sourceId, Long productId, String reason) {
		transactionTemplate.executeWithoutResult(status -> {
			passageRepository.deleteHardBySourceIdAndProductId(sourceId, productId);
			sourceRepository.findById(sourceId).ifPresent(source -> {
				source.setStatus(RelaySourceStatus.SKIPPED);
				source.setErrorMessage(reason != null ? reason : "Skipped");
				sourceRepository.save(source);
			});
		});
	}

	private void markSourceFailed(Long sourceId, Long productId, String errorMsg) {
		transactionTemplate.executeWithoutResult(status -> {
			passageRepository.deleteHardBySourceIdAndProductId(sourceId, productId);
			sourceRepository.findById(sourceId).ifPresent(source -> {
				source.setStatus(RelaySourceStatus.FAILED);
				source.setErrorMessage(errorMsg != null ? errorMsg : "Failed to read source");
				sourceRepository.save(source);
			});
		});
	}

	private record EmbeddedChunk(String text, String locator, String vectorLiteral) {
	}

	private void appendThreadMessage(Long productId, RelayThreadWho who, String text) {
		transactionTemplate.executeWithoutResult(status -> {
			productRepository.findById(productId).ifPresent(product -> saveThreadMessage(product, who, text));
		});
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
	}
}

