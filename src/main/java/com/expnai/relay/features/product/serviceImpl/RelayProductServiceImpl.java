package com.expnai.relay.features.product.serviceImpl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import com.expnai.relay.common.exception.BusinessRuleException;
import com.expnai.relay.common.exception.InvalidSessionException;
import com.expnai.relay.common.exception.ResourceNotFoundException;
import com.expnai.relay.common.web.ApiResponse;
import com.expnai.relay.domain.entity.RelayAuditEvent;
import com.expnai.relay.domain.entity.RelayProduct;
import com.expnai.relay.domain.entity.RelaySource;
import com.expnai.relay.domain.entity.RelaySourceProfile;
import com.expnai.relay.domain.entity.RelayThreadMessage;
import com.expnai.relay.domain.entity.RelayUser;
import com.expnai.relay.domain.enums.RelayBuildMode;
import com.expnai.relay.domain.enums.RelayProductStage;
import com.expnai.relay.domain.enums.RelaySourceKind;
import com.expnai.relay.domain.enums.RelaySourceStatus;
import com.expnai.relay.domain.enums.RelayThreadWho;
import com.expnai.relay.domain.repository.RelayAuditEventRepository;
import com.expnai.relay.domain.repository.RelayPassageRepository;
import com.expnai.relay.domain.repository.RelayProductRepository;
import com.expnai.relay.domain.repository.RelaySourceProfileRepository;
import com.expnai.relay.domain.repository.RelaySourceRepository;
import com.expnai.relay.domain.repository.RelayThreadMessageRepository;
import com.expnai.relay.domain.repository.RelayUserRepository;
import com.expnai.relay.features.ai.RelayAiCredentialService;
import com.expnai.relay.features.auth.security.RelayUserPrincipal;
import com.expnai.relay.features.product.dto.request.RelayAddSourcesRequest;
import com.expnai.relay.features.product.dto.request.RelayCreateProductRequest;
import com.expnai.relay.features.product.dto.request.RelaySourceInputDto;
import com.expnai.relay.features.product.dto.request.RelaySuggestChangeRequest;
import com.expnai.relay.features.product.dto.response.RelayProductResponse;
import com.expnai.relay.features.product.ingest.SourceIngestionWorker;
import com.expnai.relay.features.product.mapper.RelayProductMapper;
import com.expnai.relay.features.product.service.RelayProductService;
import com.expnai.relay.features.product.storage.FileStorageService;

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
	private final RelayThreadMessageRepository threadMessageRepository;
	private final RelayAuditEventRepository auditEventRepository;
	private final RelayUserRepository userRepository;
	private final RelayProductMapper productMapper;
	private final FileStorageService fileStorageService;
	private final RelayAiCredentialService aiCredentialService;
	private final SourceIngestionWorker sourceIngestionWorker;

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

		String input = request != null && request.getInput() != null ? request.getInput().trim() : "";
		RelayBuildMode mode = parseBuildMode(request != null ? request.getMode() : null);
		boolean forElse = request != null && Boolean.TRUE.equals(request.getForElse());
		String clientName = request != null && request.getClient() != null ? request.getClient().trim() : "";
		int pendingFiles = (request != null && request.getPendingFilesCount() != null)
				? Math.max(0, request.getPendingFilesCount())
				: 0;

		RelayProduct product = new RelayProduct();
		product.setUser(user);
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
		if (totalSources > 0) {
			product.setBuildStep(0);
			product.setStepStatus("todo");
			String greeting = "Got it"
					+ (!input.isEmpty() ? ": **" + input + "**" : "")
					+ ". " + totalSources + " source" + (totalSources > 1 ? "s" : "") + " to read first"
					+ (!clientName.isEmpty() ? " for " + clientName : "")
					+ ". I read them, index every passage so the product can cite it, and show you what I found before anything is written. About $0.25 in AI.";
			saveThreadMessage(product, RelayThreadWho.RELAY, greeting);
		}
		else {
			String greeting = !input.isEmpty()
					? "Got it: **" + input + "**. Let me find where that is worth money. I read what people search for, what already sells, what reviews complain about, and come back with four profitable pockets."
					: "No input is fine. I’ll propose pockets from what sells this month.";
			saveThreadMessage(product, RelayThreadWho.RELAY, greeting);
			product.setBuildStep(1);
			product.setStepStatus("todo");
			saveThreadMessage(product, RelayThreadWho.RELAY,
					"Next: **Profitable pockets**. About $0.30 in AI, a minute.");
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
			if (!"running".equals(product.getStepStatus())) {
				product.setStepStatus("todo");
				productRepository.save(product);
			}
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

		// Do not allow proceed to bypass the review gate on step 0
		if (product.getBuildStep() == 0 && "review".equals(product.getStepStatus())) {
			return ApiResponse.ok("Awaiting approval of source summary", toResponse(product));
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
					"Next: **Profitable pockets**. About $0.30 in AI, a minute.");
			return ApiResponse.ok("Step 0 completed", toResponse(product));
		}

		return ApiResponse.ok("OK", toResponse(product));
	}

	@Override
	@Transactional
	public ApiResponse<RelayProductResponse> approve(Long productId) {
		RelayUser user = requireCurrentUser();
		RelayProduct product = requireOwnedProduct(productId);

		if (!"review".equals(product.getStepStatus())) {
			return ApiResponse.ok("OK", toResponse(product));
		}

		recordAudit(user, product, "PRODUCT_APPROVE", "Approved step " + product.getBuildStep());
		saveThreadMessage(product, RelayThreadWho.YOU, "Looks good.");

		if (product.getBuildStep() == 0) {
			product.setBuildStep(1);
			product.setStepStatus("todo");
			product.setStepProgress(null);
			product = productRepository.save(product);
			saveThreadMessage(product, RelayThreadWho.RELAY,
					"Next: **Profitable pockets**. About $0.30 in AI, a minute.");
		}

		return ApiResponse.ok("Step approved", toResponse(product));
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
		}
		product.setStepStatus("todo");
		product.setStepProgress(null);
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
		return ApiResponse.ok("Product paused", toResponse(product));
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
			sourceProfileRepository.deleteHardByProductId(product.getId());
			product.setBuildStep(0);
			if (!"running".equals(product.getStepStatus())) {
				product.setStepStatus("todo");
			}
			productRepository.save(product);
		}
	}

	private RelayProductResponse toResponse(RelayProduct product) {
		List<RelaySource> sources = sourceRepository.findByProductIdOrderByCreatedAtAsc(product.getId());
		RelaySourceProfile profile = sourceProfileRepository.findByProductId(product.getId()).orElse(null);
		List<RelayThreadMessage> messages = threadMessageRepository
				.findByProductIdOrderByCreatedAtAscIdAsc(product.getId());
		return productMapper.toDto(product, sources, profile, messages);
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

