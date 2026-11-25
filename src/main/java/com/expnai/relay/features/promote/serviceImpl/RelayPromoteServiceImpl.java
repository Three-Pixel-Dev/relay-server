package com.expnai.relay.features.promote.serviceImpl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.expnai.relay.common.exception.BusinessRuleException;
import com.expnai.relay.common.exception.InvalidSessionException;
import com.expnai.relay.common.exception.ResourceNotFoundException;
import com.expnai.relay.common.web.ApiResponse;
import com.expnai.relay.domain.entity.RelayAuditEvent;
import com.expnai.relay.domain.entity.RelayProduct;
import com.expnai.relay.domain.entity.RelayPromoAsset;
import com.expnai.relay.domain.entity.RelayUser;
import com.expnai.relay.domain.entity.RelayWhopProduct;
import com.expnai.relay.domain.enums.RelayProductStage;
import com.expnai.relay.domain.enums.RelayPromoAssetStatus;
import com.expnai.relay.domain.enums.RelayPromoAssetType;
import com.expnai.relay.domain.enums.RelayServiceStatus;
import com.expnai.relay.domain.repository.RelayAuditEventRepository;
import com.expnai.relay.domain.repository.RelayProductRepository;
import com.expnai.relay.domain.repository.RelayPromoAssetRepository;
import com.expnai.relay.domain.repository.RelayServiceConnectionRepository;
import com.expnai.relay.domain.repository.RelayUserRepository;
import com.expnai.relay.domain.repository.RelayWhopProductRepository;
import com.expnai.relay.features.auth.security.RelayUserPrincipal;
import com.expnai.relay.features.distribute.catalog.DistributeChannelDef;
import com.expnai.relay.features.product.dto.response.RelayProductResponse;
import com.expnai.relay.features.product.service.RelayDistributePromoteReader;
import com.expnai.relay.features.product.service.RelayProductService;
import com.expnai.relay.features.promote.higgsfield.RelayHiggsfieldCredentialService;
import com.expnai.relay.features.promote.service.RelayPromoteService;
import com.expnai.relay.features.promote.worker.PromoteVideoWorker;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RelayPromoteServiceImpl implements RelayPromoteService {

	private static final List<String> REACH_SERVICES = List.of("youtube", "tiktok", "instagram");
	private static final List<String> REST_TITLES = List.of(
			"The one decision first",
			"Set the distance",
			"Read the light",
			"Ask for space",
			"Leave early",
			"Recover the walk",
			"14-day plan",
			"Decision sheet",
			"Worksheet tour");

	private final RelayProductRepository productRepository;
	private final RelayUserRepository userRepository;
	private final RelayPromoAssetRepository promoAssetRepository;
	private final RelayServiceConnectionRepository connectionRepository;
	private final RelayWhopProductRepository whopProductRepository;
	private final RelayAuditEventRepository auditEventRepository;
	private final RelayProductService productService;
	private final RelayHiggsfieldCredentialService higgsfieldCredentialService;
	private final PromoteVideoWorker promoteVideoWorker;

	@Override
	@Transactional
	public ApiResponse<RelayProductResponse> start(Long productId) {
		RelayProduct product = requirePromoteUnlocked(productId);
		requireHiggsfield(product.getUser().getId());

		List<RelayPromoAsset> existing = videos(productId);
		if (!existing.isEmpty()) {
			throw new BusinessRuleException(
					"Promo already started. Approve, regenerate, or schedule from the current videos.");
		}

		RelayUser user = requireCurrentUser();
		recordAudit(user, product, "PROMOTE_START",
				"Start first promo video via Higgsfield (estimate $"
						+ RelayDistributePromoteReader.PROMOTE_FIRST_VIDEO_USD
						+ " of ~$"
						+ RelayDistributePromoteReader.PROMOTE_TOTAL_ESTIMATE_USD
						+ ")");

		RelayPromoAsset first = newVideo(product, "3 things inside", "0:20", RelayPromoAssetStatus.RUNNING);
		promoAssetRepository.save(first);

		Long uid = user.getId();
		Long pid = product.getId();
		Long assetId = first.getId();
		runAfterCommit(() -> promoteVideoWorker.generateAsync(pid, uid, assetId, true));

		return productService.getProduct(productId);
	}

	@Override
	@Transactional
	public ApiResponse<RelayProductResponse> approve(Long productId) {
		RelayProduct product = requirePromoteUnlocked(productId);
		List<RelayPromoAsset> existing = videos(productId);
		RelayPromoAsset first = requireGateVideo(existing);
		requireHiggsfield(product.getUser().getId());

		RelayUser user = requireCurrentUser();
		recordAudit(user, product, "PROMOTE_APPROVE",
				"Approved first promo video; enqueue remaining 9 via Higgsfield (estimate $"
						+ RelayDistributePromoteReader.PROMOTE_REMAINING_USD
						+ ")");

		first.setStatus(RelayPromoAssetStatus.DONE);
		promoAssetRepository.save(first);

		List<Long> restIds = new ArrayList<>();
		int i = 0;
		for (String title : REST_TITLES) {
			int seconds = 18 + (i % 10);
			RelayPromoAsset rest = newVideo(
					product,
					title,
					"0:" + String.format(Locale.ROOT, "%02d", seconds),
					RelayPromoAssetStatus.RUNNING);
			promoAssetRepository.save(rest);
			restIds.add(rest.getId());
			i++;
		}

		Long uid = user.getId();
		Long pid = product.getId();
		runAfterCommit(() -> {
			for (Long assetId : restIds) {
				promoteVideoWorker.generateAsync(pid, uid, assetId, false);
			}
		});

		return productService.getProduct(productId);
	}

	@Override
	@Transactional
	public ApiResponse<RelayProductResponse> redo(Long productId) {
		RelayProduct product = requirePromoteUnlocked(productId);
		requireHiggsfield(product.getUser().getId());
		List<RelayPromoAsset> existing = videos(productId);
		requireGateVideo(existing);

		RelayUser user = requireCurrentUser();
		recordAudit(user, product, "PROMOTE_REDO", "Regenerate first promo video via Higgsfield");

		for (RelayPromoAsset asset : existing) {
			asset.delete();
			promoAssetRepository.save(asset);
		}

		RelayPromoAsset first = newVideo(product, "3 things inside", "0:20", RelayPromoAssetStatus.RUNNING);
		promoAssetRepository.save(first);

		Long uid = user.getId();
		Long pid = product.getId();
		Long assetId = first.getId();
		runAfterCommit(() -> promoteVideoWorker.generateAsync(pid, uid, assetId, true));

		return productService.getProduct(productId);
	}

	@Override
	@Transactional
	public ApiResponse<RelayProductResponse> schedule(Long productId) {
		RelayProduct product = requirePromoteUnlocked(productId);
		List<RelayPromoAsset> existing = videos(productId);
		if (existing.isEmpty()) {
			throw new BusinessRuleException("Make and approve the promo videos before scheduling.");
		}
		boolean scheduled = existing.stream().anyMatch(a -> a.getStatus() == RelayPromoAssetStatus.SCHEDULED);
		if (scheduled) {
			return productService.getProduct(productId);
		}
		boolean allDone = existing.stream().allMatch(a -> a.getStatus() == RelayPromoAssetStatus.DONE);
		if (!allDone || existing.size() < 10) {
			throw new BusinessRuleException(
					"Approve the first video and finish the set of 10 before scheduling.");
		}
		requireReachAccount(product.getUser().getId());

		RelayUser user = requireCurrentUser();
		recordAudit(user, product, "PROMOTE_SCHEDULE",
				"Schedule 10 Shorts one per day (stub — nothing posts yet)");

		for (RelayPromoAsset asset : existing) {
			asset.setStatus(RelayPromoAssetStatus.SCHEDULED);
			promoAssetRepository.save(asset);
		}

		return productService.getProduct(productId);
	}

	private void requireHiggsfield(Long userId) {
		if (DistributeChannelDef.isComingSoon("higgsfield")) {
			throw new BusinessRuleException(
					"Higgsfield is coming soon in Connections. You can't make promo videos yet.");
		}
		boolean connected = connectionRepository
				.findFirstByUserIdAndServiceIdIgnoreCaseAndStatusOrderByCreatedAtDesc(
						userId, "higgsfield", RelayServiceStatus.CONNECTED)
				.isPresent();
		if (!connected) {
			// Allow env fallback used by credential service.
			try {
				higgsfieldCredentialService.requireCredentials(userId);
			}
			catch (BusinessRuleException ex) {
				throw new BusinessRuleException("Add your Higgsfield key in Connections first.");
			}
		}
	}

	private void requireReachAccount(Long userId) {
		boolean anyComingSoonOnly = true;
		for (String serviceId : REACH_SERVICES) {
			if (!DistributeChannelDef.isComingSoon(serviceId)) {
				anyComingSoonOnly = false;
				boolean connected = connectionRepository
						.findFirstByUserIdAndServiceIdIgnoreCaseAndStatusOrderByCreatedAtDesc(
								userId, serviceId, RelayServiceStatus.CONNECTED)
						.isPresent();
				if (connected) {
					return;
				}
			}
		}
		if (anyComingSoonOnly) {
			throw new BusinessRuleException(
					"YouTube, TikTok and Instagram are coming soon in Connections. You can't schedule yet.");
		}
		throw new BusinessRuleException(
				"Connect YouTube, TikTok or Instagram in Connections before scheduling.");
	}

	private List<RelayPromoAsset> videos(Long productId) {
		return promoAssetRepository.findByProductIdAndAssetTypeOrderByIdAsc(productId, RelayPromoAssetType.VIDEO);
	}

	private static RelayPromoAsset requireGateVideo(List<RelayPromoAsset> existing) {
		if (existing.isEmpty()) {
			throw new BusinessRuleException("Start the first promo video before approving or regenerating.");
		}
		RelayPromoAsset first = existing.get(0);
		if (first.getStatus() != RelayPromoAssetStatus.GATE && first.getStatus() != RelayPromoAssetStatus.REVIEW) {
			throw new BusinessRuleException(
					"The first video is not waiting for approval. Promote is past the gate.");
		}
		if (existing.size() > 1) {
			throw new BusinessRuleException("The rest of the videos are already queued. Nothing to approve.");
		}
		return first;
	}

	private static RelayPromoAsset newVideo(
			RelayProduct product,
			String title,
			String duration,
			RelayPromoAssetStatus status) {
		RelayPromoAsset asset = new RelayPromoAsset();
		asset.setProduct(product);
		asset.setAssetType(RelayPromoAssetType.VIDEO);
		asset.setTitle(title);
		asset.setDuration(duration);
		asset.setStatus(status);
		asset.setUrl(null);
		return asset;
	}

	private RelayProduct requirePromoteUnlocked(Long productId) {
		RelayProduct product = productRepository.findByIdAndUserIdForUpdate(productId, currentUserId())
				.orElseThrow(() -> new ResourceNotFoundException("Product not found"));
		if (isPromoteUnlocked(product)) {
			return product;
		}
		throw new BusinessRuleException(
				"Promote unlocks after the product is built and listed (or stage is distribute).");
	}

	private boolean isPromoteUnlocked(RelayProduct product) {
		RelayProductStage stage = product.getStage();
		if (stage == RelayProductStage.DISTRIBUTE || stage == RelayProductStage.PUBLISH) {
			return true;
		}
		return whopProductRepository.findByProductId(product.getId())
				.map(RelayWhopProduct::getCheckoutUrl)
				.filter(url -> url != null && !url.isBlank())
				.isPresent();
	}

	private void recordAudit(RelayUser user, RelayProduct product, String action, String detail) {
		RelayAuditEvent event = new RelayAuditEvent();
		event.setUser(user);
		event.setProduct(product);
		event.setAction(action);
		event.setDetail(detail);
		auditEventRepository.save(event);
	}

	private static void runAfterCommit(Runnable action) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					action.run();
				}
			});
		}
		else {
			action.run();
		}
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
