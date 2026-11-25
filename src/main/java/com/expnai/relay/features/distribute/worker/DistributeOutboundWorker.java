package com.expnai.relay.features.distribute.worker;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.expnai.relay.common.persistence.AuditUserContext;
import com.expnai.relay.domain.entity.RelayBatch;
import com.expnai.relay.domain.entity.RelayBrand;
import com.expnai.relay.domain.entity.RelayChannel;
import com.expnai.relay.domain.entity.RelayProduct;
import com.expnai.relay.domain.entity.RelayServiceConnection;
import com.expnai.relay.domain.entity.RelayWhopProduct;
import com.expnai.relay.domain.enums.RelayServiceStatus;
import com.expnai.relay.domain.repository.RelayBatchRepository;
import com.expnai.relay.domain.repository.RelayBrandRepository;
import com.expnai.relay.domain.repository.RelayChannelRepository;
import com.expnai.relay.domain.repository.RelayServiceConnectionRepository;
import com.expnai.relay.domain.repository.RelayWhopProductRepository;
import com.expnai.relay.features.pinterest.PinterestApiClient;
import com.expnai.relay.features.pinterest.PinterestTokenService;
import com.expnai.relay.features.pinterest.config.PinterestProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * After week approve: create Pinterest pins when a public image URL is available;
 * enforce paid $50 hard stop by pausing the campaign.
 */
@Service
@RequiredArgsConstructor
public class DistributeOutboundWorker {

	private static final Logger log = LoggerFactory.getLogger(DistributeOutboundWorker.class);

	private final RelayBatchRepository batchRepository;
	private final RelayChannelRepository channelRepository;
	private final RelayBrandRepository brandRepository;
	private final RelayWhopProductRepository whopProductRepository;
	private final RelayServiceConnectionRepository connectionRepository;
	private final PinterestTokenService pinterestTokenService;
	private final PinterestApiClient pinterestApiClient;
	private final PinterestProperties pinterestProperties;
	private final ObjectMapper objectMapper;
	private final TransactionTemplate transactionTemplate;

	@Async("relayIngestionTaskExecutor")
	public void processBatchAsync(Long batchId, Long userId) {
		AuditUserContext.setCurrentUserId(userId);
		try {
			transactionTemplate.executeWithoutResult(status -> processBatch(batchId));
		}
		catch (Exception ex) {
			log.error("Distribute outbound batch {} failed: {}", batchId, ex.getMessage(), ex);
		}
		finally {
			AuditUserContext.clear();
		}
	}

	@SuppressWarnings("unchecked")
	private void processBatch(Long batchId) {
		RelayBatch batch = batchRepository.findById(batchId).orElse(null);
		if (batch == null || batch.getChannel() == null) {
			return;
		}
		RelayChannel channel = channelRepository.findById(batch.getChannel().getId()).orElse(null);
		if (channel == null || !channel.isEnabled()) {
			return;
		}
		String type = channel.getChannelType() != null
				? channel.getChannelType().toLowerCase(Locale.ROOT)
				: "";
		Map<String, Object> payload = readMap(batch.getItemsJson());
		if ("pinterest".equals(type)) {
			runPinterestPins(channel, batch, payload);
		}
		else if ("paid".equals(type)) {
			enforcePaidHardStop(channel, payload);
			batch.setItemsJson(writeJson(payload));
			batchRepository.save(batch);
		}
	}

	@SuppressWarnings("unchecked")
	private void runPinterestPins(RelayChannel channel, RelayBatch batch, Map<String, Object> payload) {
		RelayProduct product = channel.getProduct();
		if (product == null) {
			return;
		}
		Long userId = product.getUser() != null ? product.getUser().getId() : null;
		if (userId == null) {
			return;
		}
		Optional<RelayServiceConnection> connOpt = connectionRepository
				.findFirstByUserIdAndServiceIdIgnoreCaseAndStatusOrderByCreatedAtDesc(
						userId, "pinterest", RelayServiceStatus.CONNECTED);
		if (connOpt.isEmpty()) {
			payload.put("note", "Pinterest disconnected; pins left queued.");
			batch.setItemsJson(writeJson(payload));
			batchRepository.save(batch);
			return;
		}
		String access = pinterestTokenService.requireAccessToken(connOpt.get());
		Map<String, Object> settings = readMap(channel.getSettingsJson());
		List<Map<String, Object>> boards = (List<Map<String, Object>>) settings.getOrDefault("boards", List.of());
		String boardId = boards.isEmpty() ? null : String.valueOf(boards.get(0).get("id"));
		String imageUrl = resolvePublicCoverUrl(product.getId());
		String link = buildUtmLink(product.getId(), String.valueOf(payload.getOrDefault("utm", "utm_source=relay")));
		List<Map<String, Object>> actions = (List<Map<String, Object>>) payload.getOrDefault("actions", List.of());
		List<Map<String, Object>> updated = new ArrayList<>();
		int posted = 0;
		for (Map<String, Object> action : actions) {
			Map<String, Object> next = new LinkedHashMap<>(action);
			if (!"pin".equals(String.valueOf(action.get("type")))) {
				updated.add(next);
				continue;
			}
			if (imageUrl == null || boardId == null || boardId.isBlank()) {
				next.put("status", "queued");
				next.put("note", imageUrl == null
						? "Needs a publicly reachable cover image URL for Pinterest media_source"
						: "Missing board id");
				updated.add(next);
				continue;
			}
			// Rate-limit: post at most 2 pins immediately per approve; rest stay queued for scheduler.
			if (posted >= 2) {
				next.put("status", "queued");
				next.put("note", "Queued for daily schedule (2/day)");
				updated.add(next);
				continue;
			}
			try {
				String title = productLabel(product) + " — pin " + (posted + 1);
				var pin = pinterestApiClient.createPin(
						access, boardId, title, "From Relay · " + title, link, imageUrl);
				next.put("status", "posted");
				next.put("pinId", pin.id());
				next.put("postedAt", Instant.now().toString());
				posted++;
			}
			catch (Exception ex) {
				next.put("status", "failed");
				next.put("note", ex.getMessage());
				log.warn("Pin create failed: {}", ex.getMessage());
			}
			updated.add(next);
		}
		payload.put("actions", updated);
		payload.put("posted", posted > 0);
		if (posted > 0) {
			batch.setPostedAt(Instant.now());
			Integer clicks = channel.getClicks();
			// clicks stay real-only — do not invent
			channel.setClicks(clicks != null ? clicks : 0);
			channelRepository.save(channel);
		}
		batch.setItemsJson(writeJson(payload));
		batchRepository.save(batch);
	}

	@SuppressWarnings("unchecked")
	private void enforcePaidHardStop(RelayChannel channel, Map<String, Object> payload) {
		Map<String, Object> settings = readMap(channel.getSettingsJson());
		String campaignId = stringVal(settings.get("campaignId"));
		String adAccountId = stringVal(settings.get("adAccountId"));
		boolean hardStopped = Boolean.TRUE.equals(settings.get("hardStopped"));
		long lifetimeCap = longVal(settings.get("lifetimeBudgetMicros"),
				pinterestProperties.getPaidLifetimeBudgetMicros());
		long spent = longVal(settings.get("spentMicros"), 0L);

		RelayProduct product = channel.getProduct();
		Long userId = product != null && product.getUser() != null ? product.getUser().getId() : null;
		if (userId != null && campaignId != null && adAccountId != null) {
			connectionRepository
					.findFirstByUserIdAndServiceIdIgnoreCaseAndStatusOrderByCreatedAtDesc(
							userId, "pinterest", RelayServiceStatus.CONNECTED)
					.ifPresent(conn -> {
						try {
							String access = pinterestTokenService.requireAccessToken(conn);
							Long remote = pinterestApiClient.getCampaignLifetimeSpendMicros(
									access, adAccountId, campaignId);
							if (remote != null) {
								settings.put("spentMicros", remote);
							}
							long effective = remote != null ? remote : spent;
							if (effective >= lifetimeCap || hardStopped) {
								pinterestApiClient.pauseCampaign(access, adAccountId, campaignId);
								settings.put("hardStopped", true);
								settings.put("hardStoppedAt", Instant.now().toString());
								settings.put("note", "Hard stop: lifetime spend ≥ $50. Campaign paused.");
							}
						}
						catch (Exception ex) {
							log.warn("Paid hard-stop check failed: {}", ex.getMessage());
						}
					});
		}
		else if (spent >= lifetimeCap) {
			settings.put("hardStopped", true);
			settings.put("note", "Hard stop recorded locally ($50).");
		}
		channel.setSettingsJson(writeJson(settings));
		channelRepository.save(channel);
		payload.put("hardStopped", settings.get("hardStopped"));
		payload.put("spentMicros", settings.get("spentMicros"));
	}

	/**
	 * Cover images are served under authenticated API paths — not public for Pinterest.
	 * Only use an absolute http(s) URL if brand somehow has one; otherwise leave pins queued.
	 */
	private String resolvePublicCoverUrl(Long productId) {
		RelayBrand brand = brandRepository.findByProductId(productId).orElse(null);
		if (brand == null || brand.getCoverImageKeysJson() == null) {
			return null;
		}
		// Future: upload bytes via Pinterest media API. For now require public URL env override.
		String override = System.getenv("RELAY_PUBLIC_PIN_IMAGE_URL");
		if (override != null && (override.startsWith("http://") || override.startsWith("https://"))) {
			return override.trim();
		}
		return null;
	}

	private String buildUtmLink(Long productId, String utm) {
		String checkout = whopProductRepository.findByProductId(productId)
				.map(RelayWhopProduct::getCheckoutUrl)
				.filter(u -> u != null && !u.isBlank())
				.orElse(null);
		if (checkout == null) {
			return null;
		}
		String sep = checkout.contains("?") ? "&" : "?";
		return checkout + sep + utm;
	}

	private static String productLabel(RelayProduct product) {
		return product.getName() != null && !product.getName().isBlank() ? product.getName() : "Product";
	}

	private Map<String, Object> readMap(String json) {
		if (json == null || json.isBlank()) {
			return new LinkedHashMap<>();
		}
		try {
			return objectMapper.readValue(json, new TypeReference<>() {
			});
		}
		catch (Exception ex) {
			return new LinkedHashMap<>();
		}
	}

	private String writeJson(Map<String, Object> map) {
		try {
			return objectMapper.writeValueAsString(map);
		}
		catch (Exception ex) {
			return "{}";
		}
	}

	private static String stringVal(Object value) {
		if (value == null) {
			return null;
		}
		String s = String.valueOf(value).trim();
		return s.isEmpty() || "null".equals(s) ? null : s;
	}

	private static long longVal(Object value, long fallback) {
		if (value instanceof Number n) {
			return n.longValue();
		}
		if (value != null) {
			try {
				return Long.parseLong(String.valueOf(value));
			}
			catch (NumberFormatException ignored) {
			}
		}
		return fallback;
	}
}
