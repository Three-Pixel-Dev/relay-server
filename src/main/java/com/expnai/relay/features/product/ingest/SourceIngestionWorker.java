package com.expnai.relay.features.product.ingest;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.expnai.relay.common.logging.RelayTrace;
import com.expnai.relay.common.persistence.AuditUserContext;
import com.expnai.relay.domain.entity.RelayPassage;
import com.expnai.relay.domain.entity.RelayPocket;
import com.expnai.relay.domain.entity.RelayProduct;
import com.expnai.relay.domain.entity.RelaySection;
import com.expnai.relay.domain.entity.RelaySource;
import com.expnai.relay.domain.entity.RelaySourceProfile;
import com.expnai.relay.domain.entity.RelayThreadMessage;
import com.expnai.relay.domain.enums.RelaySourceKind;
import com.expnai.relay.domain.enums.RelaySourceStatus;
import com.expnai.relay.domain.enums.RelayThreadWho;
import com.expnai.relay.domain.repository.RelayPassageRepository;
import com.expnai.relay.domain.repository.RelayPocketRepository;
import com.expnai.relay.domain.repository.RelayProductRepository;
import com.expnai.relay.domain.repository.RelaySectionRepository;
import com.expnai.relay.domain.repository.RelaySourceProfileRepository;
import com.expnai.relay.domain.repository.RelaySourceRepository;
import com.expnai.relay.domain.repository.RelayThreadMessageRepository;
import com.expnai.relay.features.ai.AiPricingService;
import com.expnai.relay.features.ai.GeminiEmbeddingService;
import com.expnai.relay.features.ai.SourceSummaryLlmService;
import com.expnai.relay.features.ai.SourceSummaryLlmService.GeneratedSourceProfile;
import com.expnai.relay.features.ai.SourceSummaryLlmService.ThemeItem;
import com.expnai.relay.features.product.dto.response.RelayProductResponse;
import com.expnai.relay.features.product.ingest.SourceParserService.ParseResult;
import com.expnai.relay.features.product.ingest.SourceParserService.PassageChunk;
import com.expnai.relay.features.product.ingest.SourceQualityService.QualityCheckResult;
import com.expnai.relay.features.product.mapper.RelayProductMapper;
import com.expnai.relay.features.product.sse.ProductSseHub;
import com.expnai.relay.features.retrieval.PassageRetrievalService;
import com.expnai.relay.features.retrieval.RetrievedPassage;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SourceIngestionWorker {

	private static final Logger log = LoggerFactory.getLogger(SourceIngestionWorker.class);
	private static final int THEME_RETRIEVAL_TOP_K = 8;
	private static final double THEME_HIT_MIN_SCORE = 0.35;
	private static final int THEME_QUERY_MAX_CHARS = 400;

	private final RelayProductRepository productRepository;
	private final RelaySourceRepository sourceRepository;
	private final RelayPassageRepository passageRepository;
	private final RelaySourceProfileRepository sourceProfileRepository;
	private final RelayPocketRepository pocketRepository;
	private final RelaySectionRepository sectionRepository;
	private final RelayThreadMessageRepository threadMessageRepository;
	private final SourceParserService sourceParserService;
	private final SourceQualityService sourceQualityService;
	private final GeminiEmbeddingService geminiEmbeddingService;
	private final PassageRetrievalService passageRetrievalService;
	private final SourceSummaryLlmService sourceSummaryLlmService;
	private final AiPricingService aiPricingService;
	private final RelayProductMapper productMapper;
	private final ProductSseHub productSseHub;
	private final TransactionTemplate transactionTemplate;
	private final ObjectMapper objectMapper;

	@Async("relayIngestionTaskExecutor")
	public void ingestAndSummarizeAsync(
			Long productId,
			Long userId,
			String embedApiKey,
			String chatApiKey) {
		AuditUserContext.setCurrentUserId(userId);
		RelayTrace.step("start product={} step=0(Sources) mode=ingestAndSummarize", productId);
		try {
			runPipeline(productId, userId, embedApiKey, chatApiKey);
			RelayTrace.step("done product={} step=0(Sources) mode=ingestAndSummarize", productId);
		}
		catch (Exception ex) {
			log.error("Unexpected failure in ingestion pipeline for product {}: {}", productId, ex.getMessage(), ex);
			failStepGracefully(productId, "Source reading encountered an unexpected error: " + ex.getMessage());
		}
		finally {
			AuditUserContext.clear();
		}
	}

	@Async("relayIngestionTaskExecutor")
	public void summarizeExistingPassagesAsync(
			Long productId,
			Long userId,
			String embedApiKey,
			String chatApiKey) {
		AuditUserContext.setCurrentUserId(userId);
		try {
			List<RelaySource> sources = sourceRepository.findByProductIdOrderByCreatedAtAsc(productId);
			long totalPassages = passageRepository.countByProductId(productId);
			if (totalPassages == 0) {
				failStepGracefully(productId,
						"No indexed passages found to summarize. Press Proceed to read your sources.");
				return;
			}
			int videosRead = (int) sources.stream()
					.filter(s -> s.getStatus() == RelaySourceStatus.READY && s.getKind() == RelaySourceKind.YOUTUBE)
					.count();
			int docsRead = (int) sources.stream()
					.filter(s -> s.getStatus() == RelaySourceStatus.READY
							&& (s.getKind() == RelaySourceKind.FILE || s.getKind() == RelaySourceKind.WEB
									|| s.getKind() == RelaySourceKind.INSTAGRAM))
					.count();
			List<String> progressLines = new ArrayList<>();
			summarizeAndSaveProfile(
					productId,
					sources,
					videosRead,
					docsRead,
					(int) totalPassages,
					embedApiKey,
					chatApiKey,
					progressLines);
		}
		catch (Exception ex) {
			log.error("Unexpected failure summarizing existing passages for product {}: {}",
					productId, ex.getMessage(), ex);
			failStepGracefully(productId, "Source summary encountered an unexpected error: " + ex.getMessage());
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
			long mediaCount = sources.stream()
					.filter(s -> s.getKind() == RelaySourceKind.FILE)
					.filter(s -> FfmpegAudioService.isMediaFilename(s.getName()))
					.count();
			long videoCount = sources.stream()
					.filter(s -> s.getKind() == RelaySourceKind.FILE)
					.filter(s -> FfmpegAudioService.isVideoFilename(s.getName()))
					.count();
			if (videoCount > 0 && videoCount == fileCount) {
				addProgress(productId, progressLines,
						"Transcribing audio and selecting unique scenes from "
								+ videoCount + " video" + (videoCount > 1 ? "s" : ""));
			}
			else if (mediaCount > 0 && mediaCount == fileCount) {
				addProgress(productId, progressLines,
						"Transcribing " + mediaCount + " audio file" + (mediaCount > 1 ? "s" : "")
								+ " and splitting into passages");
			}
			else if (mediaCount > 0) {
				addProgress(productId, progressLines,
						"Reading documents, transcribing audio, and splitting into passages");
			}
			else {
				addProgress(productId, progressLines,
						"Reading " + fileCount + " document" + (fileCount > 1 ? "s" : "")
								+ " and splitting into passages");
			}
		}
		if (webCount > 0) {
			addProgress(productId, progressLines, "Reading the linked page");
		}
		if (igCount > 0) {
			addProgress(productId, progressLines, "Instagram: reading public profile bio and post captions");
		}

		int totalVideosRead = 0;
		int totalDocsRead = 0;
		boolean addedIndexingProgress = false;

		for (RelaySource source : sources) {
			if (isProductCancelled(productId)) {
				return;
			}
			markSourceReading(source.getId(), productId);
			try {
				ParseResult parsed = sourceParserService.parseSource(
						source,
						chatApiKey,
						line -> addProgress(productId, progressLines, line),
						productId);
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
							chunk.text(),
							productId,
							0);
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
				String friendlyErr = (source.getKind() == RelaySourceKind.WEB
						|| source.getKind() == RelaySourceKind.YOUTUBE
						|| source.getKind() == RelaySourceKind.INSTAGRAM)
						? "We couldn't read this link"
						: (sourceEx.getMessage() != null && !sourceEx.getMessage().isBlank()
								? sourceEx.getMessage()
								: "We couldn't read this source");
				markSourceFailed(source.getId(), productId, friendlyErr);
				appendThreadMessage(productId, RelayThreadWho.RELAY,
						friendlyErr + " (**" + source.getName() + "**).");
			}
		}

		if (isProductCancelled(productId)) {
			return;
		}

		long totalPassages = passageRepository.countByProductId(productId);
		if (totalPassages == 0) {
			boolean anyLink = webCount > 0 || ytCount > 0 || igCount > 0;
			String failMsg = anyLink
					? "We couldn't read this link. Replace or remove it and press Proceed again."
					: "No passages could be indexed from the sources. Replace or remove the failed source and press Proceed again.";
			failStepGracefully(productId, failMsg);
			return;
		}

		RelayProduct snapshot = productRepository.findById(productId).orElse(null);
		if (snapshot == null || snapshot.isCancelled()) {
			return;
		}

		List<RelayPassage> indexedPassages = passageRepository.findByProductIdOrderByIdAsc(productId);
		QualityCheckResult quality = sourceQualityService.evaluate(chatApiKey, snapshot, sources, indexedPassages);
		if (quality.failsGate() && !snapshot.isQualityOverride()) {
			String threadText = quality.nonTeachable()
					? "Sources are not suitable for a paid digital product."
					: "Sources look thin.";
			transactionTemplate.executeWithoutResult(status -> {
				RelayProduct product = productRepository.findById(productId).orElse(null);
				if (product == null) {
					return;
				}
				sourceProfileRepository.deleteHardByProductId(productId);
				product.setStepProgress(null);
				product.setStepStatus("quality_gate");
				product.setQualityWarning(quality.warningMessage());
				productRepository.save(product);
				saveThreadMessage(product, RelayThreadWho.RELAY, threadText);
			});
			publishTerminalProduct(productId);
			return;
		}

		summarizeAndSaveProfile(
				productId,
				sources,
				totalVideosRead,
				totalDocsRead,
				(int) totalPassages,
				embedApiKey,
				chatApiKey,
				progressLines);
	}

	private void summarizeAndSaveProfile(
			Long productId,
			List<RelaySource> sources,
			int totalVideosRead,
			int totalDocsRead,
			int totalPassages,
			String embedApiKey,
			String chatApiKey,
			List<String> progressLines) {
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
				embedApiKey,
				0);
		if (topPassages == null || topPassages.isEmpty()) {
			failStepGracefully(productId, "We couldn't read enough content from these sources to summarize them.");
			return;
		}

		GeneratedSourceProfile generated = sourceSummaryLlmService.summarizePassages(
				chatApiKey,
				snapshot,
				topPassages,
				totalPassages);

		List<ThemeItem> scoredThemes = scoreThemesFromRetrieval(
				productId,
				generated.themes(),
				totalPassages,
				embedApiKey);

		final int finalVideos = totalVideosRead;
		final int finalDocs = totalDocsRead;
		final int finalPassages = totalPassages;
		final String sanitizedNiche = sanitizeNiche(generated.niche(), sources);
		final boolean isViable = generated.viable() && !sanitizedNiche.isBlank();

		transactionTemplate.executeWithoutResult(status -> {
			RelayProduct product = productRepository.findById(productId).orElse(null);
			if (product == null) {
				return;
			}
			sourceProfileRepository.deleteHardByProductId(productId);
			RelaySourceProfile profile = new RelaySourceProfile();
			profile.setProduct(product);
			profile.setNiche(sanitizedNiche);
			profile.setTransformation(generated.transformation());
			try {
				profile.setThemesJson(objectMapper.writeValueAsString(scoredThemes));
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
			profile.setViable(isViable);
			sourceProfileRepository.save(profile);

			if (product.isAutoNamed() && !sanitizedNiche.isBlank()) {
				String nextName = sanitizedNiche.length() > 120
						? sanitizedNiche.substring(0, 120).trim()
						: sanitizedNiche;
				product.setName(nextName);
				product.setAutoNamed(false);
			}

			product.setStepProgress(null);
			if (!product.isQualityOverride()) {
				product.setQualityWarning(null);
			}
			else if (product.getQualityWarning() == null || product.getQualityWarning().isBlank()) {
				product.setQualityWarning("Built from thin sources (" + finalPassages
						+ " passage" + (finalPassages == 1 ? "" : "s") + ")");
			}
			product.setStepStatus("review");
			productRepository.save(product);

			String summaryMsg = buildSummaryReadyMessage(finalVideos, finalDocs, finalPassages);
			saveThreadMessage(product, RelayThreadWho.RELAY, summaryMsg);
		});

		publishTerminalProduct(productId);
	}

	private List<ThemeItem> scoreThemesFromRetrieval(
			Long productId,
			List<ThemeItem> rawThemes,
			int totalPassages,
			String embedApiKey) {
		if (rawThemes == null || rawThemes.isEmpty()) {
			return List.of();
		}
		int topK = Math.min(THEME_RETRIEVAL_TOP_K, Math.max(3, totalPassages));
		double minScore = totalPassages < 20 ? 0.45 : THEME_HIT_MIN_SCORE;

		List<ThemeItem> scored = new ArrayList<>(rawThemes.size());
		for (ThemeItem theme : rawThemes) {
			String themeQuery = buildThemeQuery(theme.t(), theme.d());
			int hits = 0;
			try {
				List<RetrievedPassage> matches = passageRetrievalService.retrieveWithApiKey(
						productId,
						themeQuery,
						topK,
						embedApiKey,
						0);
				hits = (int) matches.stream()
						.filter(p -> p.score() >= minScore)
						.count();
			}
			catch (Exception ex) {
				log.warn("Per-theme retrieval scoring failed for product {} theme '{}': {}",
						productId, theme.t(), ex.getMessage());
			}
			String hitLevel = SourceSummaryLlmService.levelFromHits(hits);
			String clampedLevel = SourceSummaryLlmService.clampLevel(hitLevel, totalPassages);
			scored.add(new ThemeItem(theme.t(), theme.d(), clampedLevel, hits));
		}
		return scored;
	}

	private String buildThemeQuery(String title, String description) {
		String t = title != null ? title.trim() : "";
		String d = description != null ? description.trim() : "";
		String combined = (t + " " + d).trim();
		if (combined.isEmpty()) {
			return "core method theme";
		}
		return combined.length() > THEME_QUERY_MAX_CHARS
				? combined.substring(0, THEME_QUERY_MAX_CHARS)
				: combined;
	}

	private String buildSummarySearchQuery(RelayProduct product, List<RelaySource> sources) {
		StringBuilder sb = new StringBuilder();
		if (product.getName() != null && !product.getName().isBlank()) {
			sb.append(product.getName().trim()).append(' ');
		}
		if (product.getInput() != null && !product.getInput().isBlank()) {
			sb.append(product.getInput().trim()).append(' ');
		}
		if (product.getStepNote() != null && !product.getStepNote().isBlank()) {
			sb.append(product.getStepNote().trim()).append(' ');
		}
		sb.append("core method niche transformation themes audience voice");
		return sb.toString().trim();
	}

	private String sanitizeNiche(String rawNiche, List<RelaySource> sources) {
		if (rawNiche == null || rawNiche.isBlank()) {
			return "";
		}
		String trimmed = rawNiche.trim();
		String normalized = stripFileExtension(trimmed).toLowerCase(Locale.ROOT);
		if (normalized.equals("invoice")
				|| normalized.equals("receipt")
				|| normalized.equals("statement")
				|| normalized.equals("billing statement")
				|| normalized.equals("bank statement")
				|| normalized.equals("purchase order")
				|| normalized.equals("document")
				|| normalized.equals("untitled")
				|| normalized.equals("untitled product")) {
			return "";
		}
		if (sources != null) {
			for (RelaySource s : sources) {
				if (s.getName() != null && !s.getName().isBlank()) {
					String sourceBase = stripFileExtension(s.getName().trim()).toLowerCase(Locale.ROOT);
					if (!sourceBase.isEmpty() && (normalized.equals(sourceBase)
							|| normalized.equals(s.getName().trim().toLowerCase(Locale.ROOT)))) {
						return "";
					}
				}
			}
		}
		return trimmed;
	}

	private String stripFileExtension(String name) {
		int dot = name.lastIndexOf('.');
		return (dot > 0) ? name.substring(0, dot).trim() : name;
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
				+ (passages == 1 ? " passage" : " passages")
				+ " indexed. Here is what the sources say. If it matches, I use it to find the pockets and to write in this voice. Uploaded documents take priority over what I infer.";
	}

	private boolean isProductCancelled(Long productId) {
		return productRepository.findById(productId)
				.map(RelayProduct::isCancelled)
				.orElse(true);
	}

	private void addProgress(Long productId, List<String> progressLines, String newLine) {
		if (!progressLines.isEmpty() && isSameOcrProgressLine(progressLines.get(progressLines.size() - 1), newLine)) {
			progressLines.set(progressLines.size() - 1, newLine);
		}
		else {
			progressLines.add(newLine);
		}
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

	private boolean isSameOcrProgressLine(String previous, String next) {
		if (previous == null || next == null) {
			return false;
		}
		if (!previous.startsWith("OCR page ") || !next.startsWith("OCR page ")) {
			return false;
		}
		int prevOf = previous.indexOf(" of ");
		int nextOf = next.indexOf(" of ");
		return prevOf > 0 && nextOf > 0 && previous.substring(prevOf).equals(next.substring(nextOf));
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

