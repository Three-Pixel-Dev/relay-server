package com.expnai.relay.features.product.serviceImpl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.expnai.relay.common.exception.BusinessRuleException;
import com.expnai.relay.common.exception.InvalidSessionException;
import com.expnai.relay.common.exception.ResourceNotFoundException;
import com.expnai.relay.common.web.ApiResponse;
import com.expnai.relay.domain.entity.RelayAuditEvent;
import com.expnai.relay.domain.entity.RelayBrand;
import com.expnai.relay.domain.entity.RelayCitation;
import com.expnai.relay.domain.entity.RelayPocket;
import com.expnai.relay.domain.entity.RelayProduct;
import com.expnai.relay.domain.entity.RelayProductFile;
import com.expnai.relay.domain.entity.RelaySection;
import com.expnai.relay.domain.entity.RelaySource;
import com.expnai.relay.domain.entity.RelaySourceProfile;
import com.expnai.relay.domain.entity.RelayThreadMessage;
import com.expnai.relay.domain.entity.RelayUser;
import com.expnai.relay.domain.enums.RelayBuildMode;
import com.expnai.relay.domain.enums.RelayProductStage;
import com.expnai.relay.domain.enums.RelayProductType;
import com.expnai.relay.domain.enums.RelaySectionStatus;
import com.expnai.relay.domain.enums.RelaySourceKind;
import com.expnai.relay.domain.enums.RelaySourceStatus;
import com.expnai.relay.domain.enums.RelayThreadWho;
import com.expnai.relay.domain.repository.RelayAiUsageEventRepository;
import com.expnai.relay.domain.repository.RelayAuditEventRepository;
import com.expnai.relay.domain.repository.RelayBrandRepository;
import com.expnai.relay.domain.repository.RelayCitationRepository;
import com.expnai.relay.domain.repository.RelayPassageRepository;
import com.expnai.relay.domain.repository.RelayPocketRepository;
import com.expnai.relay.domain.repository.RelayProductFileRepository;
import com.expnai.relay.domain.repository.RelayProductRepository;
import com.expnai.relay.domain.repository.RelaySectionRepository;
import com.expnai.relay.domain.repository.RelaySourceProfileRepository;
import com.expnai.relay.domain.repository.RelaySourceRepository;
import com.expnai.relay.domain.repository.RelayThreadMessageRepository;
import com.expnai.relay.domain.repository.RelayUserRepository;
import com.expnai.relay.features.ai.AiPricingService;
import com.expnai.relay.features.ai.BrandingLlmService;
import com.expnai.relay.features.ai.RelayAiCredentialService;
import com.expnai.relay.features.auth.security.RelayUserPrincipal;
import com.expnai.relay.features.product.dto.request.RelayAddSectionRequest;
import com.expnai.relay.features.product.dto.request.RelayAddSourcesRequest;
import com.expnai.relay.features.product.dto.request.RelayCreateProductRequest;
import com.expnai.relay.features.product.dto.request.RelaySourceInputDto;
import com.expnai.relay.features.product.dto.request.RelaySuggestChangeRequest;
import com.expnai.relay.features.product.dto.request.RelayUpdateBrandRequest;
import com.expnai.relay.features.product.dto.response.RelayProductResponse;
import com.expnai.relay.features.product.export.ReadyExportWorker;
import com.expnai.relay.features.product.ingest.BrandingGenerationWorker;
import com.expnai.relay.features.product.ingest.ManuscriptGenerationWorker;
import com.expnai.relay.features.product.ingest.PocketGenerationWorker;
import com.expnai.relay.features.product.ingest.SourceIngestionWorker;
import com.expnai.relay.features.product.ingest.StructureGenerationWorker;
import com.expnai.relay.features.product.mapper.RelayProductMapper;
import com.expnai.relay.features.product.service.RelayProductService;
import com.expnai.relay.features.product.sse.ProductSseHub;
import com.expnai.relay.features.product.storage.FileStorageService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RelayProductServiceImpl implements RelayProductService {

	private static final String[] BUILD_STEPS = {
			"Sources and what you know",
			"Profitable pockets",
			"Product type",
			"Structure",
			"Manuscript",
			"Branding",
			"Ready"
	};

	private final RelayProductRepository productRepository;
	private final RelaySourceRepository sourceRepository;
	private final RelayPassageRepository passageRepository;
	private final RelaySourceProfileRepository sourceProfileRepository;
	private final RelayPocketRepository pocketRepository;
	private final RelaySectionRepository sectionRepository;
	private final RelayCitationRepository citationRepository;
	private final RelayBrandRepository brandRepository;
	private final RelayProductFileRepository productFileRepository;
	private final RelayAiUsageEventRepository aiUsageEventRepository;
	private final RelayThreadMessageRepository threadMessageRepository;
	private final RelayAuditEventRepository auditEventRepository;
	private final RelayUserRepository userRepository;
	private final RelayProductMapper productMapper;
	private final FileStorageService fileStorageService;
	private final RelayAiCredentialService aiCredentialService;
	private final AiPricingService aiPricingService;
	private final SourceIngestionWorker sourceIngestionWorker;
	private final PocketGenerationWorker pocketGenerationWorker;
	private final StructureGenerationWorker structureGenerationWorker;
	private final ManuscriptGenerationWorker manuscriptGenerationWorker;
	private final BrandingGenerationWorker brandingGenerationWorker;
	private final ReadyExportWorker readyExportWorker;
	private final ProductSseHub productSseHub;
	private final ObjectMapper objectMapper;

	@Override
	@Transactional(readOnly = true)
	public ApiResponse<List<RelayProductResponse>> listProducts() {
		Long userId = currentUserId();
		List<RelayProductResponse> products = productRepository.findByUserIdOrderByCreatedAtDesc(userId)
				.stream()
				.map(this::toResponse)
				.toList();
		return ApiResponse.ok("OK", products);
	}

	@Override
	@Transactional(readOnly = true)
	public ApiResponse<RelayProductResponse> getProduct(Long productId) {
		RelayProduct product = requireOwnedProduct(productId);
		return ApiResponse.ok("OK", toResponse(product));
	}

	@Override
	@Transactional
	public ApiResponse<RelayProductResponse> createProduct(RelayCreateProductRequest request) {
		RelayUser user = requireCurrentUser();

		String name = request != null && request.getName() != null ? request.getName().trim() : "";
		if (name.isEmpty()) {
			throw new BusinessRuleException("Product name is required.");
		}
		String input = request != null && request.getInput() != null ? request.getInput().trim() : "";
		RelayBuildMode mode = parseBuildMode(request != null ? request.getMode() : null);
		boolean forElse = request != null && Boolean.TRUE.equals(request.getForElse());
		String clientName = request != null && request.getClient() != null ? request.getClient().trim() : "";
		int pendingFiles = (request != null && request.getPendingFilesCount() != null)
				? Math.max(0, request.getPendingFilesCount())
				: 0;

		RelayProduct product = new RelayProduct();
		product.setUser(user);
		product.setName(name);
		product.setInput(input);
		product.setMode(mode);
		product.setForElse(forElse);
		product.setClientName(clientName);
		product.setStage(RelayProductStage.BUILD);
		product.setBuildStep(0);
		product.setStepStatus("todo");
		product.setSpend(BigDecimal.ZERO);
		product = productRepository.save(product);

		List<RelaySource> createdSources = new ArrayList<>();
		if (request != null && request.getSources() != null) {
			for (RelaySourceInputDto srcDto : request.getSources()) {
				RelaySource savedSource = createSourceEntity(product, srcDto);
				if (savedSource != null) {
					createdSources.add(savedSource);
				}
			}
		}

		int totalSources = createdSources.size() + pendingFiles;
		String displaySubject = !input.isEmpty() ? input : name;
		if (totalSources > 0) {
			product.setBuildStep(0);
			product.setStepStatus("todo");
			String greeting = "Got it"
					+ (!displaySubject.isEmpty() ? ": **" + displaySubject + "**" : "")
					+ ". " + totalSources + " source" + (totalSources > 1 ? "s" : "") + " to read first"
					+ (!clientName.isEmpty() ? " for " + clientName : "")
					+ ". I read them, index every passage so the product can cite it, and show you what I found before anything is written. "
					+ formatEstimateSentence(aiPricingService.estimateSourcesUsd(), ".");
			saveThreadMessage(product, RelayThreadWho.RELAY, greeting);
		}
		else {
			String greeting = !displaySubject.isEmpty()
					? "Got it: **" + displaySubject + "**. Let me find where that is worth money. I read what people search for, what already sells, what reviews complain about, and come back with four profitable pockets."
					: "No input is fine. I’ll propose pockets from what sells this month.";
			saveThreadMessage(product, RelayThreadWho.RELAY, greeting);
			product.setBuildStep(1);
			product.setStepStatus("todo");
			saveThreadMessage(product, RelayThreadWho.RELAY,
					"Next: **Profitable pockets**. "
							+ formatEstimateSentence(aiPricingService.estimatePocketsUsd(), ", a minute."));
		}

		product = productRepository.save(product);
		recordAudit(user, product, "PRODUCT_CREATE", "Created product (sources=" + totalSources + ")");

		if (mode == RelayBuildMode.FAST && !createdSources.isEmpty() && pendingFiles == 0) {
			try {
				return proceed(product.getId());
			}
			catch (BusinessRuleException ignored) {
				// If AI keys are not connected yet, return the created product on step 0 so UI prompts for keys.
			}
		}

		return ApiResponse.created("Product created", toResponse(product));
	}

	@Override
	@Transactional
	public ApiResponse<Void> deleteProduct(Long productId) {
		RelayProduct product = requireOwnedProduct(productId);
		product.setChosenPocket(null);
		productRepository.saveAndFlush(product);
		aiUsageEventRepository.deleteHardByProductId(productId);
		productFileRepository.deleteHardByProductId(productId);
		brandRepository.deleteHardByProductId(productId);
		citationRepository.deleteHardByProductId(productId);
		sectionRepository.deleteHardByProductId(productId);
		pocketRepository.deleteHardByProductId(productId);
		passageRepository.deleteHardByProductId(productId);
		sourceProfileRepository.deleteHardByProductId(productId);
		fileStorageService.deleteProductDirectory(productId);

		for (RelaySource source : sourceRepository.findByProductIdOrderByCreatedAtAsc(productId)) {
			source.delete();
			sourceRepository.save(source);
		}
		for (RelayThreadMessage msg : threadMessageRepository.findByProductIdOrderByCreatedAtAscIdAsc(productId)) {
			msg.delete();
			threadMessageRepository.save(msg);
		}
		product.delete();
		productRepository.save(product);
		return ApiResponse.ok("Product deleted");
	}

	@Override
	@Transactional
	public ApiResponse<RelayProductResponse> addSources(Long productId, RelayAddSourcesRequest request) {
		RelayProduct product = requireOwnedProduct(productId);
		if (request != null && request.getSources() != null) {
			for (RelaySourceInputDto srcDto : request.getSources()) {
				createSourceEntity(product, srcDto);
			}
		}
		resetStepZeroIfApplicable(product);
		return ApiResponse.ok("Sources added", toResponse(product));
	}

	@Override
	@Transactional
	public ApiResponse<RelayProductResponse> uploadSources(Long productId, List<MultipartFile> files) {
		RelayProduct product = requireOwnedProduct(productId);
		if (files == null || files.isEmpty()) {
			throw new BusinessRuleException("No files provided for upload.");
		}
		for (MultipartFile file : files) {
			if (file == null || file.isEmpty()) {
				continue;
			}
			String storageKey = fileStorageService.storeMultipartFile(productId, file);
			RelaySource source = new RelaySource();
			source.setProduct(product);
			source.setKind(RelaySourceKind.FILE);
			source.setName(file.getOriginalFilename() != null ? file.getOriginalFilename() : "document");
			source.setFileSize(file.getSize());
			source.setStorageKey(storageKey);
			source.setStatus(RelaySourceStatus.PENDING);
			sourceRepository.save(source);
		}
		resetStepZeroIfApplicable(product);
		return ApiResponse.ok("Files uploaded", toResponse(product));
	}

	@Override
	@Transactional
	public ApiResponse<RelayProductResponse> removeSource(Long productId, Long sourceId) {
		RelayProduct product = requireOwnedProduct(productId);
		RelaySource source = sourceRepository.findByIdAndProductId(sourceId, productId)
				.orElseThrow(() -> new ResourceNotFoundException("Source not found"));

		passageRepository.deleteHardBySourceIdAndProductId(sourceId, productId);
		fileStorageService.deleteFile(source.getStorageKey());
		source.delete();
		sourceRepository.save(source);

		if (product.getBuildStep() == 0 && product.getStage() == RelayProductStage.BUILD) {
			sourceProfileRepository.deleteHardByProductId(productId);
			product.setQualityWarning(null);
			product.setQualityOverride(false);
			if (!"running".equals(product.getStepStatus())) {
				product.setStepStatus("todo");
			}
			productRepository.save(product);
		}
		return ApiResponse.ok("Source removed", toResponse(product));
	}

	@Override
	@Transactional
	public ApiResponse<RelayProductResponse> proceed(Long productId) {
		RelayUser user = requireCurrentUser();
		RelayProduct product = requireOwnedProduct(productId);

		if (product.getStage() != RelayProductStage.BUILD || "running".equals(product.getStepStatus())) {
			return ApiResponse.ok("OK", toResponse(product));
		}

		// Do not allow proceed to bypass review or quality_gate on step 0, step 1, or step 2
		if (product.getBuildStep() == 0 && "review".equals(product.getStepStatus())) {
			return ApiResponse.ok("Awaiting approval of source summary", toResponse(product));
		}
		if (product.getBuildStep() == 0 && "quality_gate".equals(product.getStepStatus())) {
			return ApiResponse.ok("Awaiting source quality decision", toResponse(product));
		}
		if (product.getBuildStep() == 1 && "review".equals(product.getStepStatus())) {
			return ApiResponse.ok("Awaiting pocket selection", toResponse(product));
		}
		if (product.getBuildStep() == 2 && "review".equals(product.getStepStatus())) {
			return ApiResponse.ok("Awaiting product type selection", toResponse(product));
		}
		if (product.getBuildStep() == 3 && "review".equals(product.getStepStatus())) {
			return ApiResponse.ok("Awaiting structure approval", toResponse(product));
		}
		if (product.getBuildStep() == 4 && "gate".equals(product.getStepStatus())) {
			return ApiResponse.ok("Awaiting voice approval", toResponse(product));
		}
		if (product.getBuildStep() == 4 && "review".equals(product.getStepStatus())) {
			return ApiResponse.ok("Awaiting manuscript approval", toResponse(product));
		}
		if (product.getBuildStep() == 5 && "review".equals(product.getStepStatus())) {
			return ApiResponse.ok("Awaiting branding approval", toResponse(product));
		}

		// Server guard: persist Proceed audit event BEFORE any read or model call
		recordAudit(user, product, "PRODUCT_PROCEED", "Proceed on step " + product.getBuildStep());

		if (product.getBuildStep() == 0) {
			List<RelaySource> sources = sourceRepository.findByProductIdOrderByCreatedAtAsc(productId);
			RelaySourceProfile profile = sourceProfileRepository.findByProductId(productId).orElse(null);

			if (profile != null) {
				if (!"review".equals(product.getStepStatus())) {
					product.setStepStatus("review");
					product.setStepProgress(null);
					product = productRepository.save(product);
				}
				return ApiResponse.ok("Awaiting approval of source summary", toResponse(product));
			}

			if (!sources.isEmpty()) {
				boolean hasReadableSources = sources.stream()
						.anyMatch(s -> s.getKind() != RelaySourceKind.INSTAGRAM);
				final String embedKey;
				final String chatKey;
				if (hasReadableSources) {
					embedKey = aiCredentialService.requireGeminiEmbeddingKey(user.getId());
					chatKey = aiCredentialService.requireChatModelKey(user.getId());
				}
				else {
					embedKey = "";
					chatKey = "";
				}

				product.setCancelled(false);
				product.setStepStatus("running");
				product.setStepProgress("[\"Starting source reader…\"]");
				product = productRepository.saveAndFlush(product);

				final Long pid = product.getId();
				final Long uid = user.getId();
				if (TransactionSynchronizationManager.isSynchronizationActive()) {
					TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
						@Override
						public void afterCommit() {
							sourceIngestionWorker.ingestAndSummarizeAsync(pid, uid, embedKey, chatKey);
						}
					});
				}
				else {
					sourceIngestionWorker.ingestAndSummarizeAsync(pid, uid, embedKey, chatKey);
				}

				return ApiResponse.ok("Reading sources", toResponse(product));
			}

			// No sources attached -> complete step 0 and advance to step 1
			product.setStepProgress(null);
			product.setStepStatus("todo");
			product.setBuildStep(1);
			product = productRepository.save(product);
			saveThreadMessage(product, RelayThreadWho.RELAY,
					"Next: **Profitable pockets**. "
							+ formatEstimateSentence(aiPricingService.estimatePocketsUsd(), ", a minute."));
			return ApiResponse.ok("Step 0 completed", toResponse(product));
		}

		if (product.getBuildStep() == 1) {
			List<RelayPocket> existingPockets = pocketRepository.findByProductIdOrderByIdAsc(productId);
			if (!existingPockets.isEmpty() && product.getChosenPocket() == null) {
				if (!"review".equals(product.getStepStatus())) {
					product.setStepStatus("review");
					product.setStepProgress(null);
					product = productRepository.save(product);
				}
				return ApiResponse.ok("Awaiting pocket selection", toResponse(product));
			}

			final String chatKey = aiCredentialService.requireChatModelKey(user.getId());

			product.setCancelled(false);
			product.setStepStatus("running");
			product.setStepProgress("[\"Starting market research…\"]");
			product = productRepository.saveAndFlush(product);

			final Long pid = product.getId();
			final Long uid = user.getId();
			if (TransactionSynchronizationManager.isSynchronizationActive()) {
				TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
					@Override
					public void afterCommit() {
						pocketGenerationWorker.generatePocketsAsync(pid, uid, chatKey);
					}
				});
			}
			else {
				pocketGenerationWorker.generatePocketsAsync(pid, uid, chatKey);
			}

			return ApiResponse.ok("Finding profitable pockets", toResponse(product));
		}

		if (product.getBuildStep() == 2) {
			product.setStepStatus("review");
			product.setStepProgress(null);
			product = productRepository.save(product);
			if (product.getProductType() == null) {
				saveThreadMessage(product, RelayThreadWho.RELAY,
						"What shape should it take? Planners and workbooks sell most on Etsy; a guide teaches best; a checklist makes a strong lead magnet.");
			}
			return ApiResponse.ok("Awaiting product type selection", toResponse(product));
		}

		if (product.getBuildStep() == 3) {
			List<RelaySection> existingSections = sectionRepository.findByProductIdOrderBySortOrderAscIdAsc(productId);
			if (!existingSections.isEmpty()) {
				if (!"review".equals(product.getStepStatus())) {
					product.setStepStatus("review");
					product.setStepProgress(null);
					product = productRepository.save(product);
				}
				return ApiResponse.ok("Awaiting structure approval", toResponse(product));
			}

			final String chatKey = aiCredentialService.requireChatModelKey(user.getId());
			String resolvedEmbedKey = "";
			if (passageRepository.countByProductId(productId) > 0) {
				try {
					resolvedEmbedKey = aiCredentialService.requireGeminiEmbeddingKey(user.getId());
				}
				catch (BusinessRuleException ignored) {
				}
			}
			final String embedKey = resolvedEmbedKey;

			product.setCancelled(false);
			product.setStepStatus("running");
			product.setStepProgress("[\"Mapping the method into sections\"]");
			product = productRepository.saveAndFlush(product);

			final Long pid = product.getId();
			final Long uid = user.getId();
			if (TransactionSynchronizationManager.isSynchronizationActive()) {
				TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
					@Override
					public void afterCommit() {
						structureGenerationWorker.generateStructureAsync(pid, uid, embedKey, chatKey);
					}
				});
			}
			else {
				structureGenerationWorker.generateStructureAsync(pid, uid, embedKey, chatKey);
			}

			return ApiResponse.ok("Generating structure", toResponse(product));
		}

		if (product.getBuildStep() == 4) {
			List<RelaySection> sections = sectionRepository.findByProductIdOrderBySortOrderAscIdAsc(productId);
			if (sections.isEmpty()) {
				throw new BusinessRuleException("No sections found to write. Complete Structure first.");
			}

			final String chatKey = aiCredentialService.requireChatModelKey(user.getId());
			final String embedKey = resolveOptionalEmbedKey(productId, user.getId());

			product.setCancelled(false);
			product.setStepStatus("running");
			product.setStepProgress("[\"Starting manuscript writer…\"]");
			product = productRepository.saveAndFlush(product);

			final Long pid = product.getId();
			final Long uid = user.getId();
			if (TransactionSynchronizationManager.isSynchronizationActive()) {
				TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
					@Override
					public void afterCommit() {
						manuscriptGenerationWorker.generateManuscriptAsync(pid, uid, embedKey, chatKey);
					}
				});
			}
			else {
				manuscriptGenerationWorker.generateManuscriptAsync(pid, uid, embedKey, chatKey);
			}

			return ApiResponse.ok("Writing manuscript", toResponse(product));
		}

		if (product.getBuildStep() == 5) {
			RelayBrand existingBrand = brandRepository.findByProductId(productId).orElse(null);
			if (existingBrand != null) {
				if (!"review".equals(product.getStepStatus())) {
					product.setStepStatus("review");
					product.setStepProgress(null);
					product = productRepository.save(product);
				}
				return ApiResponse.ok("Awaiting branding approval", toResponse(product));
			}

			final String chatKey = aiCredentialService.requireChatModelKey(user.getId());
			final String imageKey = aiCredentialService.requireImageModelKey(user.getId());

			product.setCancelled(false);
			product.setQualityWarning(null);
			product.setStepStatus("running");
			product.setStepProgress("[\"Naming the cover title\"]");
			product = productRepository.saveAndFlush(product);

			final Long pid = product.getId();
			final Long uid = user.getId();
			if (TransactionSynchronizationManager.isSynchronizationActive()) {
				TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
					@Override
					public void afterCommit() {
						brandingGenerationWorker.generateBrandingAsync(pid, uid, chatKey, imageKey);
					}
				});
			}
			else {
				brandingGenerationWorker.generateBrandingAsync(pid, uid, chatKey, imageKey);
			}

			return ApiResponse.ok("Generating branding", toResponse(product));
		}

		if (product.getBuildStep() == 6) {
			List<RelayProductFile> existingFiles = productFileRepository.findByProductIdOrderByIdAsc(productId);
			if (!existingFiles.isEmpty()) {
				return ApiResponse.ok("Files already exported", toResponse(product));
			}
			brandRepository.findByProductId(productId)
					.orElseThrow(() -> new BusinessRuleException("Complete Branding before packaging files."));
			List<RelaySection> sections = sectionRepository.findByProductIdOrderBySortOrderAscIdAsc(productId);
			boolean hasDoneSection = sections.stream().anyMatch(s -> s.getStatus() == RelaySectionStatus.DONE);
			if (!hasDoneSection) {
				throw new BusinessRuleException("Write at least one manuscript section before packaging files.");
			}

			final String imageKey = aiCredentialService.requireImageModelKey(user.getId());

			product.setCancelled(false);
			product.setStepStatus("running");
			product.setStepProgress("[\"Laying out pages\"]");
			product = productRepository.saveAndFlush(product);

			final Long pid = product.getId();
			final Long uid = user.getId();
			if (TransactionSynchronizationManager.isSynchronizationActive()) {
				TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
					@Override
					public void afterCommit() {
						readyExportWorker.exportAsync(pid, uid, imageKey);
					}
				});
			}
			else {
				readyExportWorker.exportAsync(pid, uid, imageKey);
			}

			return ApiResponse.ok("Packaging export files", toResponse(product));
		}

		return ApiResponse.ok("OK", toResponse(product));
	}

	@Override
	@Transactional
	public ApiResponse<RelayProductResponse> continueQualityGate(Long productId) {
		RelayUser user = requireCurrentUser();
		RelayProduct product = requireOwnedProduct(productId);

		if (product.getStage() != RelayProductStage.BUILD || product.getBuildStep() != 0) {
			return ApiResponse.ok("OK", toResponse(product));
		}
		if (!"quality_gate".equals(product.getStepStatus())) {
			return ApiResponse.ok("OK", toResponse(product));
		}

		final String embedKey = aiCredentialService.requireGeminiEmbeddingKey(user.getId());
		final String chatKey = aiCredentialService.requireChatModelKey(user.getId());

		recordAudit(user, product, "PRODUCT_QUALITY_CONTINUE", "Continued despite source quality gate on step 0");
		saveThreadMessage(product, RelayThreadWho.YOU, "Continue anyway.");

		product.setQualityOverride(true);
		product.setQualityWarning(null);
		product.setCancelled(false);
		product.setStepStatus("running");
		product.setStepProgress("[\"Summarising niche, themes, voice and audience\"]");
		product = productRepository.saveAndFlush(product);

		final Long pid = product.getId();
		final Long uid = user.getId();
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					sourceIngestionWorker.summarizeExistingPassagesAsync(pid, uid, embedKey, chatKey);
				}
			});
		}
		else {
			sourceIngestionWorker.summarizeExistingPassagesAsync(pid, uid, embedKey, chatKey);
		}

		return ApiResponse.ok("Continuing with existing sources", toResponse(product));
	}

	@Override
	@Transactional
	public ApiResponse<RelayProductResponse> approve(Long productId) {
		RelayUser user = requireCurrentUser();
		RelayProduct product = requireOwnedProduct(productId);

		String currentStatus = product.getStepStatus();
		if (!"review".equals(currentStatus) && !"gate".equals(currentStatus)) {
			return ApiResponse.ok("OK", toResponse(product));
		}

		if (product.getBuildStep() == 4 && "gate".equals(currentStatus)) {
			final String chatKey = aiCredentialService.requireChatModelKey(user.getId());
			final String embedKey = resolveOptionalEmbedKey(productId, user.getId());

			recordAudit(user, product, "PRODUCT_APPROVE_VOICE", "Approved manuscript voice on step 4");
			saveThreadMessage(product, RelayThreadWho.YOU, "Voice approved. Write the rest.");

			product.setVoiceApproved(true);
			product.setCancelled(false);
			product.setStepStatus("running");
			product.setStepProgress("[\"Writing remaining sections…\"]");
			product = productRepository.saveAndFlush(product);

			final Long pid = product.getId();
			final Long uid = user.getId();
			if (TransactionSynchronizationManager.isSynchronizationActive()) {
				TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
					@Override
					public void afterCommit() {
						manuscriptGenerationWorker.generateManuscriptAsync(pid, uid, embedKey, chatKey);
					}
				});
			}
			else {
				manuscriptGenerationWorker.generateManuscriptAsync(pid, uid, embedKey, chatKey);
			}
			return ApiResponse.ok("Voice approved — writing remaining sections", toResponse(product));
		}

		if (!"review".equals(currentStatus)) {
			return ApiResponse.ok("OK", toResponse(product));
		}

		if (product.getBuildStep() == 2) {
			if (product.getProductType() == null) {
				throw new BusinessRuleException("Pick a product type before continuing.");
			}
			recordAudit(user, product, "PRODUCT_APPROVE", "Approved step 2 (" + product.getProductType().getCode() + ")");
			saveThreadMessage(product, RelayThreadWho.YOU, productTypeLabel(product.getProductType()));

			product.setBuildStep(3);
			product.setStepStatus("todo");
			product.setStepProgress(null);
			product.setStepNote(null);
			product = productRepository.save(product);

			saveThreadMessage(product, RelayThreadWho.RELAY,
					"Next: **Structure**. A table of contents with the moment each section owns.");
			return ApiResponse.ok("Product type confirmed", toResponse(product));
		}

		recordAudit(user, product, "PRODUCT_APPROVE", "Approved step " + product.getBuildStep());
		saveThreadMessage(product, RelayThreadWho.YOU, "Looks good.");

		if (product.getBuildStep() == 0) {
			product.setBuildStep(1);
			product.setStepStatus("todo");
			product.setStepProgress(null);
			product = productRepository.save(product);
			saveThreadMessage(product, RelayThreadWho.RELAY,
					"Next: **Profitable pockets**. "
							+ formatEstimateSentence(aiPricingService.estimatePocketsUsd(), ", a minute."));
		}
		else if (product.getBuildStep() == 3) {
			List<RelaySection> sections = sectionRepository.findByProductIdOrderBySortOrderAscIdAsc(productId);
			if (sections.isEmpty()) {
				throw new BusinessRuleException("Add at least one section before approving structure.");
			}
			product.setBuildStep(4);
			product.setStepStatus("todo");
			product.setStepProgress(null);
			product.setStepNote(null);
			product = productRepository.save(product);
			saveThreadMessage(product, RelayThreadWho.RELAY,
					"Next: **Manuscript**. " + sections.size()
							+ " sections, written one at a time. I stop after the first so you can approve the voice. "
							+ formatEstimateSentence(aiPricingService.estimateManuscriptUsd(sections.size()), "."));
		}
		else if (product.getBuildStep() == 4) {
			List<RelaySection> sections = sectionRepository.findByProductIdOrderBySortOrderAscIdAsc(productId);
			boolean allDone = !sections.isEmpty()
					&& sections.stream().allMatch(s -> s.getStatus() == RelaySectionStatus.DONE);
			if (!allDone) {
				throw new BusinessRuleException("All manuscript sections must be written before approving.");
			}
			product.setBuildStep(5);
			product.setStepStatus("todo");
			product.setStepProgress(null);
			product.setStepNote(null);
			product = productRepository.save(product);
			saveThreadMessage(product, RelayThreadWho.RELAY,
					"Next: **Branding**. Palette, type and cover from the subject. "
							+ formatEstimateSentence(aiPricingService.estimateBrandingUsd(), "."));
		}
		else if (product.getBuildStep() == 5) {
			brandRepository.findByProductId(productId)
					.orElseThrow(() -> new BusinessRuleException("Generate branding before approving."));
			product.setBuildStep(6);
			product.setStepStatus("todo");
			product.setStepProgress(null);
			product.setStepNote(null);
			product = productRepository.save(product);
			saveThreadMessage(product, RelayThreadWho.RELAY,
					"Next: **Ready**. Pages laid out, PDF, EPUB, DOCX, mockups.");
		}

		return ApiResponse.ok("Step approved", toResponse(product));
	}

	@Override
	@Transactional
	public ApiResponse<RelayProductResponse> choosePocket(Long productId, Long pocketId) {
		RelayUser user = requireCurrentUser();
		RelayProduct product = requireOwnedProduct(productId);

		if (product.getStage() != RelayProductStage.BUILD || product.getBuildStep() != 1) {
			return ApiResponse.ok("OK", toResponse(product));
		}

		RelayPocket pocket = pocketRepository.findByIdAndProductId(pocketId, productId)
				.orElseThrow(() -> new ResourceNotFoundException("Pocket not found"));

		recordAudit(user, product, "PRODUCT_CHOOSE_POCKET", "Chose pocket " + pocket.getId() + ": " + pocket.getNiche());
		saveThreadMessage(product, RelayThreadWho.YOU, "Picked: **" + pocket.getNiche() + "**");

		product.setChosenPocket(pocket);
		product.setBuildStep(2);
		product.setStepStatus("todo");
		product.setStepProgress(null);
		product.setStepNote(null);
		product = productRepository.save(product);

		saveThreadMessage(product, RelayThreadWho.RELAY,
				"Next: **Product type**. Pick the format that fits **" + pocket.getNiche() + "** best.");

		return ApiResponse.ok("Pocket chosen", toResponse(product));
	}

	@Override
	@Transactional
	public ApiResponse<RelayProductResponse> chooseType(Long productId, String typeId) {
		RelayUser user = requireCurrentUser();
		RelayProduct product = requireOwnedProduct(productId);

		if (product.getStage() != RelayProductStage.BUILD) {
			return ApiResponse.ok("OK", toResponse(product));
		}

		boolean onTypeReview = product.getBuildStep() == 2 && "review".equals(product.getStepStatus());
		boolean canReopenFromStructure = product.getBuildStep() == 3
				&& "todo".equals(product.getStepStatus())
				&& sectionRepository.findByProductIdOrderBySortOrderAscIdAsc(productId).isEmpty();
		if (!onTypeReview && !canReopenFromStructure) {
			return ApiResponse.ok("OK", toResponse(product));
		}

		final RelayProductType chosenType;
		try {
			chosenType = RelayProductType.fromCode(typeId != null ? typeId.trim() : null);
		}
		catch (IllegalArgumentException ex) {
			throw new BusinessRuleException("Unknown product type: " + typeId);
		}
		if (chosenType == null) {
			throw new BusinessRuleException("Product type is required.");
		}

		if (chosenType.equals(product.getProductType()) && onTypeReview) {
			return ApiResponse.ok("Product type already selected", toResponse(product));
		}

		recordAudit(user, product, "PRODUCT_CHOOSE_TYPE", "Selected product type " + chosenType.getCode());

		product.setProductType(chosenType);
		// Stay on / return to Product type review so the user can re-pick before confirming
		product.setBuildStep(2);
		product.setStepStatus("review");
		product.setStepProgress(null);
		product.setStepNote(null);
		product = productRepository.save(product);

		return ApiResponse.ok("Product type selected", toResponse(product));
	}

	private String productTypeLabel(RelayProductType type) {
		return switch (type) {
			case EBOOK -> "eBook / guide";
			case WORKBOOK -> "Workbook";
			case PLANNER -> "Planner / tracker";
			case CHECKLIST -> "Checklist / cheat sheet";
			case TEMPLATES -> "Template pack";
			case MINICOURSE -> "Mini-course PDF";
		};
	}

	@Override
	@Transactional
	public ApiResponse<RelayProductResponse> addSection(Long productId, RelayAddSectionRequest request) {
		RelayProduct product = requireOwnedProduct(productId);
		if (product.getStage() != RelayProductStage.BUILD
				|| product.getBuildStep() != 3
				|| !"review".equals(product.getStepStatus())) {
			throw new BusinessRuleException("Sections can only be added while reviewing Structure.");
		}
		String title = request != null && request.getTitle() != null ? request.getTitle().trim() : "";
		if (title.isEmpty()) {
			throw new BusinessRuleException("Section title is required.");
		}
		List<RelaySection> existing = sectionRepository.findByProductIdOrderBySortOrderAscIdAsc(productId);
		int nextSortOrder = existing.isEmpty() ? 0 : existing.get(existing.size() - 1).getSortOrder() + 1;

		RelaySection section = new RelaySection();
		section.setProduct(product);
		section.setTitle(title);
		section.setMoment("Your own section");
		section.setText("");
		section.setWords(0);
		section.setStatus(RelaySectionStatus.TODO);
		section.setVersion(1);
		section.setSortOrder(nextSortOrder);
		sectionRepository.save(section);

		return ApiResponse.ok("Section added", toResponse(product));
	}

	@Override
	@Transactional
	public ApiResponse<RelayProductResponse> removeSection(Long productId, Long sectionId) {
		RelayProduct product = requireOwnedProduct(productId);
		if (product.getStage() != RelayProductStage.BUILD
				|| product.getBuildStep() != 3
				|| !"review".equals(product.getStepStatus())) {
			throw new BusinessRuleException("Sections can only be removed while reviewing Structure.");
		}
		sectionRepository.findByIdAndProductId(sectionId, productId)
				.orElseThrow(() -> new ResourceNotFoundException("Section not found"));
		if (sectionRepository.countByProductId(productId) <= 1) {
			throw new BusinessRuleException("Keep at least one section in the structure.");
		}
		citationRepository.deleteHardBySectionId(sectionId);
		sectionRepository.deleteHardByIdAndProductId(sectionId, productId);
		return ApiResponse.ok("Section removed", toResponse(product));
	}

	@Override
	@Transactional
	public ApiResponse<RelayProductResponse> regenerateSection(Long productId, Long sectionId) {
		RelayUser user = requireCurrentUser();
		RelayProduct product = requireOwnedProduct(productId);
		String prevStatus = product.getStepStatus();
		if (product.getStage() != RelayProductStage.BUILD
				|| product.getBuildStep() != 4
				|| (!"gate".equals(prevStatus) && !"review".equals(prevStatus))) {
			throw new BusinessRuleException("Sections can only be regenerated during Manuscript gate or review.");
		}

		RelaySection section = sectionRepository.findByIdAndProductId(sectionId, productId)
				.orElseThrow(() -> new ResourceNotFoundException("Section not found"));

		final String chatKey = aiCredentialService.requireChatModelKey(user.getId());
		final String embedKey = resolveOptionalEmbedKey(productId, user.getId());

		recordAudit(user, product, "PRODUCT_REGENERATE_SECTION", "Regenerating section " + section.getId());
		citationRepository.deleteHardBySectionId(sectionId);
		section.setStatus(RelaySectionStatus.TODO);
		sectionRepository.save(section);

		product.setCancelled(false);
		product.setStepStatus("running");
		product.setStepProgress("[\"Regenerating " + section.getTitle().replace("\"", "'") + "…\"]");
		product = productRepository.saveAndFlush(product);

		final Long pid = product.getId();
		final Long sid = section.getId();
		final Long uid = user.getId();
		final String returnStatus = prevStatus;
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					manuscriptGenerationWorker.regenerateSingleSectionAsync(
							pid, sid, returnStatus, uid, embedKey, chatKey);
				}
			});
		}
		else {
			manuscriptGenerationWorker.regenerateSingleSectionAsync(
					pid, sid, returnStatus, uid, embedKey, chatKey);
		}

		return ApiResponse.ok("Regenerating section", toResponse(product));
	}

	@Override
	@Transactional
	public ApiResponse<RelayProductResponse> updateBrand(Long productId, RelayUpdateBrandRequest request) {
		RelayProduct product = requireOwnedProduct(productId);
		if (product.getStage() != RelayProductStage.BUILD
				|| product.getBuildStep() != 5
				|| !"review".equals(product.getStepStatus())) {
			throw new BusinessRuleException("Branding can only be edited while reviewing Branding.");
		}
		RelayBrand brand = brandRepository.findByProductId(productId)
				.orElseThrow(() -> new ResourceNotFoundException("Brand not generated yet."));

		if (request != null) {
			if (request.getTitle() != null && !request.getTitle().isBlank()) {
				brand.setTitle(request.getTitle().trim());
			}
			if (request.getPalette() != null) {
				int palette = Math.max(0, Math.min(2, request.getPalette()));
				brand.setPaletteIndex(palette);
				brand.setTypePair(BrandingLlmService.typePairForPalette(palette));
			}
			if (request.getCover() != null) {
				int cover = Math.max(0, Math.min(2, request.getCover()));
				brand.setCoverVariant(cover);
			}
			brandRepository.save(brand);
		}

		return ApiResponse.ok("Brand updated", toResponse(product));
	}

	@Override
	@Transactional
	public ApiResponse<RelayProductResponse> suggest(Long productId, RelaySuggestChangeRequest request) {
		RelayUser user = requireCurrentUser();
		RelayProduct product = requireOwnedProduct(productId);
		String text = request != null && request.getText() != null ? request.getText().trim() : "";
		if (text.isEmpty()) {
			return ApiResponse.ok("OK", toResponse(product));
		}

		recordAudit(user, product, "PRODUCT_SUGGEST", text);
		saveThreadMessage(product, RelayThreadWho.YOU, text);

		product.setStepNote(text);
		if (product.getBuildStep() == 0) {
			sourceProfileRepository.deleteHardByProductId(productId);
			product.setQualityWarning(null);
			product.setQualityOverride(false);
		}
		else if (product.getBuildStep() == 1) {
			product.setChosenPocket(null);
			productRepository.saveAndFlush(product);
			pocketRepository.deleteHardByProductId(productId);
		}
		else if (product.getBuildStep() == 2) {
			product.setProductType(null);
		}
		else if (product.getBuildStep() == 3) {
			citationRepository.deleteHardByProductId(productId);
			sectionRepository.deleteHardByProductId(productId);
		}
		else if (product.getBuildStep() == 4) {
			citationRepository.deleteHardByProductId(productId);
			for (RelaySection sec : sectionRepository.findByProductIdOrderBySortOrderAscIdAsc(productId)) {
				sec.setText("");
				sec.setWords(0);
				sec.setStatus(RelaySectionStatus.TODO);
				sectionRepository.save(sec);
			}
			product.setWordCount(0);
			product.setVoiceApproved(false);
		}
		else if (product.getBuildStep() == 5) {
			brandRepository.findByProductId(productId).ifPresent(this::deleteBrandGraphics);
			brandRepository.deleteHardByProductId(productId);
			product.setQualityWarning(null);
		}
		else if (product.getBuildStep() == 6 && product.getStage() == RelayProductStage.BUILD) {
			for (RelayProductFile oldFile : productFileRepository.findByProductIdOrderByIdAsc(productId)) {
				fileStorageService.deleteFile(oldFile.getUrl());
			}
			productFileRepository.deleteHardByProductId(productId);
		}
		if (product.getStage() == RelayProductStage.BUILD) {
			product.setStepStatus("todo");
			product.setStepProgress(null);
		}
		product = productRepository.save(product);

		int idx = Math.max(0, Math.min(product.getBuildStep(), BUILD_STEPS.length - 1));
		String stepName = BUILD_STEPS[idx].toLowerCase(Locale.ROOT);
		saveThreadMessage(product, RelayThreadWho.RELAY,
				"Got it. I’ll redo **" + stepName + "** with that. Press Proceed when ready.");

		return ApiResponse.ok("Suggestion saved", toResponse(product));
	}

	@Override
	@Transactional
	public ApiResponse<RelayProductResponse> cancel(Long productId) {
		RelayUser user = requireCurrentUser();
		RelayProduct product = requireOwnedProduct(productId);

		recordAudit(user, product, "PRODUCT_CANCEL", "Paused product at step " + product.getBuildStep());
		product.setCancelled(true);
		product.setStage(RelayProductStage.PAUSED);
		product.setStepProgress(null);
		if ("running".equals(product.getStepStatus())) {
			product.setStepStatus("todo");
		}
		product = productRepository.save(product);

		BigDecimal spend = product.getSpend() != null ? product.getSpend() : BigDecimal.ZERO;
		saveThreadMessage(product, RelayThreadWho.RELAY,
				"Paused. Everything so far is kept; $"
						+ spend.setScale(2, RoundingMode.HALF_UP)
						+ " spent on AI. Resume from My products.");
		RelayProductResponse response = toResponse(product);
		productSseHub.publish(productId, "product", response, true);
		return ApiResponse.ok("Product paused", response);
	}

	@Override
	@Transactional
	public ApiResponse<RelayProductResponse> resume(Long productId) {
		RelayUser user = requireCurrentUser();
		RelayProduct product = requireOwnedProduct(productId);

		recordAudit(user, product, "PRODUCT_RESUME", "Resumed product at step " + product.getBuildStep());
		product.setCancelled(false);
		product.setStage(RelayProductStage.BUILD);
		product = productRepository.save(product);

		saveThreadMessage(product, RelayThreadWho.RELAY, "Resumed.");
		return ApiResponse.ok("Product resumed", toResponse(product));
	}

	@Override
	@Transactional(readOnly = true)
	public ResponseEntity<Resource> downloadFile(Long productId, Long fileId) {
		requireOwnedProduct(productId);
		RelayProductFile file = productFileRepository.findByIdAndProductId(fileId, productId)
				.orElseThrow(() -> new ResourceNotFoundException("File not found"));
		Path path = fileStorageService.resolvePath(file.getUrl());
		if (!Files.exists(path) || !Files.isRegularFile(path)) {
			throw new ResourceNotFoundException("Export file is missing on disk");
		}
		String filename = path.getFileName().toString();
		MediaType mediaType = resolveExportMediaType(file.getFileType());
		return ResponseEntity.ok()
				.contentType(mediaType)
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
				.body(new FileSystemResource(path));
	}

	private MediaType resolveExportMediaType(String fileType) {
		if (fileType == null) {
			return MediaType.APPLICATION_OCTET_STREAM;
		}
		return switch (fileType.toLowerCase(Locale.ROOT)) {
			case "pdf_letter", "pdf_a4" -> MediaType.APPLICATION_PDF;
			case "docx" -> MediaType.parseMediaType(
					"application/vnd.openxmlformats-officedocument.wordprocessingml.document");
			case "epub" -> MediaType.parseMediaType("application/epub+zip");
			case "mockups", "html" -> MediaType.parseMediaType("application/zip");
			default -> MediaType.APPLICATION_OCTET_STREAM;
		};
	}

	@Override
	@Transactional(readOnly = true)
	public ResponseEntity<Resource> getBrandCoverImage(Long productId, int variant) {
		requireOwnedProduct(productId);
		if (variant < 0 || variant > 2) {
			throw new ResourceNotFoundException("Cover variant not found");
		}
		RelayBrand brand = brandRepository.findByProductId(productId)
				.orElseThrow(() -> new ResourceNotFoundException("Brand not found"));
		if (brand.getCoverImageKeysJson() == null || brand.getCoverImageKeysJson().isBlank()) {
			throw new ResourceNotFoundException("Cover image not generated");
		}
		try {
			List<String> keys = objectMapper.readValue(
					brand.getCoverImageKeysJson(), new TypeReference<List<String>>() {
					});
			if (variant >= keys.size() || keys.get(variant) == null || keys.get(variant).isBlank()) {
				throw new ResourceNotFoundException("Cover image variant not available");
			}
			return streamImageFile(keys.get(variant));
		}
		catch (ResourceNotFoundException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new ResourceNotFoundException("Cover image unavailable");
		}
	}

	@Override
	@Transactional(readOnly = true)
	public ResponseEntity<Resource> getSectionGraphicImage(Long productId, Long sectionId) {
		requireOwnedProduct(productId);
		RelayBrand brand = brandRepository.findByProductId(productId)
				.orElseThrow(() -> new ResourceNotFoundException("Brand not found"));
		if (brand.getPageGraphicsJson() == null || brand.getPageGraphicsJson().isBlank()) {
			throw new ResourceNotFoundException("Section graphic not generated");
		}
		try {
			Map<String, String> keys = objectMapper.readValue(
					brand.getPageGraphicsJson(), new TypeReference<Map<String, String>>() {
					});
			String key = keys != null ? keys.get(String.valueOf(sectionId)) : null;
			if (key == null || key.isBlank()) {
				throw new ResourceNotFoundException("Section graphic not found");
			}
			return streamImageFile(key);
		}
		catch (ResourceNotFoundException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new ResourceNotFoundException("Section graphic unavailable");
		}
	}

	private ResponseEntity<Resource> streamImageFile(String storageKey) {
		Path path = fileStorageService.resolvePath(storageKey);
		if (!Files.exists(path) || !Files.isRegularFile(path)) {
			throw new ResourceNotFoundException("Image file missing on disk");
		}
		return ResponseEntity.ok()
				.contentType(MediaType.IMAGE_PNG)
				.cacheControl(CacheControl.noCache())
				.body(new FileSystemResource(path));
	}

	private void deleteBrandGraphics(RelayBrand brand) {
		if (brand == null) {
			return;
		}
		if (brand.getCoverImageKeysJson() != null && !brand.getCoverImageKeysJson().isBlank()) {
			try {
				List<String> keys = objectMapper.readValue(
						brand.getCoverImageKeysJson(), new TypeReference<List<String>>() {
						});
				for (String key : keys) {
					fileStorageService.deleteFile(key);
				}
			}
			catch (Exception ignored) {
			}
		}
		if (brand.getPageGraphicsJson() != null && !brand.getPageGraphicsJson().isBlank()) {
			try {
				Map<String, String> keys = objectMapper.readValue(
						brand.getPageGraphicsJson(), new TypeReference<Map<String, String>>() {
						});
				if (keys != null) {
					for (String key : keys.values()) {
						fileStorageService.deleteFile(key);
					}
				}
			}
			catch (Exception ignored) {
			}
		}
	}

	@Override
	@Transactional(readOnly = true)
	public SseEmitter subscribeEvents(Long productId) {
		RelayProduct product = requireOwnedProduct(productId);
		RelayProductResponse snapshot = toResponse(product);
		SseEmitter emitter = productSseHub.subscribe(productId);
		boolean running = product.getStage() == RelayProductStage.BUILD
				&& "running".equals(product.getStepStatus());
		productSseHub.sendToEmitter(productId, emitter, "snapshot", snapshot, false);
		if (!running) {
			productSseHub.sendToEmitter(productId, emitter, "product", snapshot, true);
		}
		return emitter;
	}

	private RelaySource createSourceEntity(RelayProduct product, RelaySourceInputDto dto) {
		if (dto == null) {
			return null;
		}
		String url = dto.getUrl() != null ? dto.getUrl().trim() : null;
		RelaySourceKind kind = resolveKind(dto.getKind(), url);
		String name = (dto.getName() != null && !dto.getName().isBlank())
				? dto.getName().trim()
				: inferLinkName(kind, url != null ? url : "Source");

		RelaySource source = new RelaySource();
		source.setProduct(product);
		source.setKind(kind);
		source.setName(name);
		source.setUrl(url);
		source.setFileSize(dto.getSize());
		if (kind == RelaySourceKind.INSTAGRAM) {
			source.setStatus(RelaySourceStatus.SKIPPED);
			source.setErrorMessage("Instagram reading requires creator OAuth approval (out of scope for Phase 1).");
		}
		else {
			source.setStatus(RelaySourceStatus.PENDING);
		}

		if (kind == RelaySourceKind.FILE) {
			String key = fileStorageService.storeSampleDocument(product.getId(), name, product.getInput());
			source.setStorageKey(key);
		}
		return sourceRepository.save(source);
	}

	private void resetStepZeroIfApplicable(RelayProduct product) {
		if (product.getStage() != RelayProductStage.BUILD) {
			return;
		}
		if (product.getBuildStep() == 0 || (product.getBuildStep() == 1 && product.getChosenPocket() == null)) {
			String prevStatus = product.getStepStatus();
			sourceProfileRepository.deleteHardByProductId(product.getId());
			product.setBuildStep(0);
			product.setQualityWarning(null);
			product.setQualityOverride(false);
			if (!"running".equals(prevStatus)) {
				product.setStepStatus("todo");
			}
			productRepository.save(product);
			if ("quality_gate".equals(prevStatus) || "review".equals(prevStatus)) {
				saveThreadMessage(product, RelayThreadWho.RELAY,
						"Sources updated. Press Proceed to re-read.");
			}
		}
	}

	private String resolveOptionalEmbedKey(Long productId, Long userId) {
		if (passageRepository.countByProductId(productId) <= 0) {
			return "";
		}
		try {
			return aiCredentialService.requireGeminiEmbeddingKey(userId);
		}
		catch (BusinessRuleException ignored) {
			return "";
		}
	}

	private RelayProductResponse toResponse(RelayProduct product) {
		List<RelaySource> sources = sourceRepository.findByProductIdOrderByCreatedAtAsc(product.getId());
		RelaySourceProfile profile = sourceProfileRepository.findByProductId(product.getId()).orElse(null);
		List<RelayPocket> pockets = pocketRepository.findByProductIdOrderByIdAsc(product.getId());
		List<RelaySection> sections = sectionRepository.findByProductIdOrderBySortOrderAscIdAsc(product.getId());
		List<RelayCitation> citations = sections.isEmpty()
				? List.of()
				: citationRepository.findBySectionIdInOrderBySectionIdAscIdAsc(
						sections.stream().map(RelaySection::getId).toList());
		RelayBrand brand = brandRepository.findByProductId(product.getId()).orElse(null);
		List<RelayProductFile> files = productFileRepository.findByProductIdOrderByIdAsc(product.getId());
		List<RelayThreadMessage> messages = threadMessageRepository
				.findByProductIdOrderByCreatedAtAscIdAsc(product.getId());
		return productMapper.toDto(product, sources, profile, pockets, sections, citations, brand, files, messages);
	}

	private void saveThreadMessage(RelayProduct product, RelayThreadWho who, String text) {
		RelayThreadMessage msg = new RelayThreadMessage();
		msg.setProduct(product);
		msg.setWho(who);
		msg.setText(text);
		threadMessageRepository.save(msg);
	}

	private void recordAudit(RelayUser user, RelayProduct product, String action, String detail) {
		RelayAuditEvent event = new RelayAuditEvent();
		event.setUser(user);
		event.setProduct(product);
		event.setAction(action);
		event.setDetail(detail);
		auditEventRepository.save(event);
	}

	private String formatEstimateSentence(Optional<BigDecimal> estimateOpt, String suffix) {
		return estimateOpt
				.map(est -> "About ~$" + est + " in AI (estimate)" + suffix)
				.orElse("AI cost estimate unavailable right now.");
	}

	private RelayBuildMode parseBuildMode(String mode) {
		if (mode != null && "fast".equalsIgnoreCase(mode.trim())) {
			return RelayBuildMode.FAST;
		}
		return RelayBuildMode.GUIDED;
	}

	private RelaySourceKind resolveKind(String rawKind, String url) {
		if (rawKind != null && !rawKind.isBlank()) {
			try {
				return RelaySourceKind.fromCode(rawKind.trim().toUpperCase(Locale.ROOT));
			}
			catch (IllegalArgumentException ignored) {
			}
		}
		if (url != null) {
			String lower = url.toLowerCase(Locale.ROOT);
			if (lower.contains("youtube.com") || lower.contains("youtu.be")) {
				return RelaySourceKind.YOUTUBE;
			}
			if (lower.contains("instagram.com") || lower.startsWith("@")) {
				return RelaySourceKind.INSTAGRAM;
			}
			return RelaySourceKind.WEB;
		}
		return RelaySourceKind.FILE;
	}

	private String inferLinkName(RelaySourceKind kind, String url) {
		String trimmed = url.replaceFirst("^https?://(www\\.)?", "").replaceFirst("/+$", "");
		if (kind == RelaySourceKind.INSTAGRAM) {
			String handle = trimmed.replaceFirst("^instagram\\.com/", "").replaceFirst("^@", "").split("[/?]")[0];
			return "@" + handle;
		}
		if (kind == RelaySourceKind.YOUTUBE) {
			String[] parts = trimmed.split("/");
			String last = parts[parts.length - 1].split("\\?")[0];
			return last.startsWith("@") ? last : "youtube.com/" + last;
		}
		return trimmed.length() > 40 ? trimmed.substring(0, 40) : trimmed;
	}

	private RelayProduct requireOwnedProduct(Long productId) {
		return productRepository.findByIdAndUserId(productId, currentUserId())
				.orElseThrow(() -> new ResourceNotFoundException("Product not found"));
	}

	private RelayUser requireCurrentUser() {
		return userRepository.findById(currentUserId())
				.orElseThrow(() -> new ResourceNotFoundException("User not found"));
	}

	private Long currentUserId() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof RelayUserPrincipal principal)) {
			throw new InvalidSessionException("Not authenticated");
		}
		return principal.getId();
	}
}

