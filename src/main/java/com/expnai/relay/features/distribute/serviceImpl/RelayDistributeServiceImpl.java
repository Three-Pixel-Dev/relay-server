package com.expnai.relay.features.distribute.serviceImpl;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

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
import com.expnai.relay.domain.entity.RelayBatch;
import com.expnai.relay.domain.entity.RelayChannel;
import com.expnai.relay.domain.entity.RelayProduct;
import com.expnai.relay.domain.entity.RelayServiceConnection;
import com.expnai.relay.domain.entity.RelayUser;
import com.expnai.relay.domain.entity.RelayWhopProduct;
import com.expnai.relay.domain.enums.RelayProductStage;
import com.expnai.relay.domain.enums.RelayServiceStatus;
import com.expnai.relay.domain.repository.RelayAuditEventRepository;
import com.expnai.relay.domain.repository.RelayBatchRepository;
import com.expnai.relay.domain.repository.RelayChannelRepository;
import com.expnai.relay.domain.repository.RelayProductRepository;
import com.expnai.relay.domain.repository.RelayServiceConnectionRepository;
import com.expnai.relay.domain.repository.RelayUserRepository;
import com.expnai.relay.domain.repository.RelayWhopProductRepository;
import com.expnai.relay.features.auth.security.RelayUserPrincipal;
import com.expnai.relay.features.distribute.catalog.DistributeChannelDef;
import com.expnai.relay.features.distribute.service.RelayDistributeService;
import com.expnai.relay.features.distribute.setup.DistributeChannelSetupStub;
import com.expnai.relay.features.distribute.worker.DistributeOutboundWorker;
import com.expnai.relay.features.product.dto.response.RelayProductResponse;
import com.expnai.relay.features.product.service.RelayProductService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RelayDistributeServiceImpl implements RelayDistributeService {

	private final RelayProductRepository productRepository;
	private final RelayUserRepository userRepository;
	private final RelayChannelRepository channelRepository;
	private final RelayBatchRepository batchRepository;
	private final RelayServiceConnectionRepository connectionRepository;
	private final RelayWhopProductRepository whopProductRepository;
	private final RelayAuditEventRepository auditEventRepository;
	private final DistributeChannelSetupStub setupStub;
	private final DistributeOutboundWorker outboundWorker;
	private final RelayProductService productService;

	@Override
	@Transactional
	public ApiResponse<RelayProductResponse> turnOn(Long productId, String channelId) {
		RelayProduct product = requireDistributeUnlocked(productId);
		DistributeChannelDef def = DistributeChannelDef.find(channelId)
				.orElseThrow(() -> new BusinessRuleException(
						"Unknown distribute channel. Use seo, pinterest, leads, relay, affiliates, or paid."));
		validateNeeds(product.getUser().getId(), def);

		RelayUser user = requireCurrentUser();
		recordAudit(user, product, "DISTRIBUTE_CHANNEL_ON", "Turn on " + def.id());

		RelayChannel channel = channelRepository
				.findByProductIdAndChannelTypeIgnoreCase(productId, def.id())
				.orElseGet(() -> {
					RelayChannel created = new RelayChannel();
					created.setProduct(product);
					created.setChannelType(def.id());
					created.setWeekNumber(0);
					created.setClicks(0);
					return created;
				});

		if (channel.isEnabled()) {
			return productService.getProduct(productId);
		}

		channel.setEnabled(false);
		channel.setSettingsJson("{\"setting\":true}");
		channel = channelRepository.saveAndFlush(channel);

		setupStub.setup(product, channel);
		channel.setEnabled(true);
		channelRepository.save(channel);

		return productService.getProduct(productId);
	}

	@Override
	@Transactional
	public ApiResponse<RelayProductResponse> turnOff(Long productId, String channelId) {
		RelayProduct product = requireDistributeUnlocked(productId);
		DistributeChannelDef def = DistributeChannelDef.find(channelId)
				.orElseThrow(() -> new BusinessRuleException(
						"Unknown distribute channel. Use seo, pinterest, leads, relay, affiliates, or paid."));

		RelayUser user = requireCurrentUser();
		Optional<RelayChannel> existing = channelRepository
				.findByProductIdAndChannelTypeIgnoreCase(productId, def.id());
		if (existing.isEmpty() || !existing.get().isEnabled()) {
			return productService.getProduct(productId);
		}

		recordAudit(user, product, "DISTRIBUTE_CHANNEL_OFF", "Turn off " + def.id());
		RelayChannel channel = existing.get();
		channel.setEnabled(false);
		// Keep settings / remote assets; clear transient setting-up flag only.
		String settings = channel.getSettingsJson();
		if (settings != null && settings.contains("\"setting\"")) {
			channel.setSettingsJson(settings.replace("\"setting\":true", "\"setting\":false"));
		}
		channelRepository.save(channel);
		return productService.getProduct(productId);
	}

	@Override
	@Transactional
	public ApiResponse<RelayProductResponse> approveWeek(Long productId) {
		RelayProduct product = requireDistributeUnlocked(productId);
		List<RelayChannel> enabled = channelRepository.findByProductIdAndEnabledTrueOrderByIdAsc(productId);
		if (enabled.isEmpty()) {
			throw new BusinessRuleException("Turn on at least one channel before approving this week's batch.");
		}

		RelayUser user = requireCurrentUser();
		recordAudit(user, product, "DISTRIBUTE_WEEK_APPROVE",
				"Approved weekly batch for " + enabled.size() + " channel(s)");

		List<Long> batchIds = new java.util.ArrayList<>();
		for (RelayChannel channel : enabled) {
			int nextWeek = (channel.getWeekNumber() != null ? channel.getWeekNumber() : 0) + 1;
			channel.setWeekNumber(nextWeek);
			channelRepository.save(channel);

			RelayBatch batch = new RelayBatch();
			batch.setChannel(channel);
			batch.setApprovedAt(Instant.now());
			batch.setPostedAt(null); // nothing posts without this approve + worker
			batch.setItemsJson(setupStub.buildWeekItemsJson(channel, nextWeek));
			batch = batchRepository.save(batch);
			batchIds.add(batch.getId());
		}

		Long uid = user.getId();
		runAfterCommit(() -> {
			for (Long batchId : batchIds) {
				outboundWorker.processBatchAsync(batchId, uid);
			}
		});

		return productService.getProduct(productId);
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

	private RelayProduct requireDistributeUnlocked(Long productId) {
		RelayProduct product = productRepository.findByIdAndUserIdForUpdate(productId, currentUserId())
				.orElseThrow(() -> new ResourceNotFoundException("Product not found"));
		if (isDistributeUnlocked(product)) {
			return product;
		}
		throw new BusinessRuleException(
				"Distribute unlocks after the product is built and listed (or stage is distribute).");
	}

	private boolean isDistributeUnlocked(RelayProduct product) {
		RelayProductStage stage = product.getStage();
		if (stage == RelayProductStage.DISTRIBUTE || stage == RelayProductStage.PUBLISH) {
			return true;
		}
		return whopProductRepository.findByProductId(product.getId())
				.map(RelayWhopProduct::getCheckoutUrl)
				.filter(url -> url != null && !url.isBlank())
				.isPresent();
	}

	private void validateNeeds(Long userId, DistributeChannelDef def) {
		for (String need : def.needsAll()) {
			assertNeedSatisfied(userId, need);
		}
		if (def.needsAny().isEmpty()) {
			return;
		}
		List<String> actionable = def.needsAny().stream()
				.filter(s -> !DistributeChannelDef.isComingSoon(s))
				.toList();
		for (String need : actionable) {
			if (isConnected(userId, need)) {
				return;
			}
		}
		if (!actionable.isEmpty()) {
			throw new BusinessRuleException(
					"Connect " + DistributeChannelDef.serviceDisplayName(actionable.get(0))
							+ " in Connections first.");
		}
		String soon = def.needsAny().get(0);
		throw new BusinessRuleException(
				DistributeChannelDef.serviceDisplayName(soon)
						+ " is coming soon in Connections. You can't turn this channel on yet.");
	}

	private void assertNeedSatisfied(Long userId, String serviceId) {
		String key = serviceId.toLowerCase(Locale.ROOT);
		String label = DistributeChannelDef.serviceDisplayName(key);
		if (DistributeChannelDef.isComingSoon(key)) {
			throw new BusinessRuleException(
					label + " is coming soon in Connections. You can't turn this channel on yet.");
		}
		if (!isConnected(userId, key)) {
			throw new BusinessRuleException("Connect " + label + " in Connections first.");
		}
	}

	private boolean isConnected(Long userId, String serviceId) {
		return connectionRepository
				.findFirstByUserIdAndServiceIdIgnoreCaseAndStatusOrderByCreatedAtDesc(
						userId, serviceId, RelayServiceStatus.CONNECTED)
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
