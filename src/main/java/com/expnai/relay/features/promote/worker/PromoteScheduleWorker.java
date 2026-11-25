package com.expnai.relay.features.promote.worker;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.expnai.relay.domain.entity.RelayPromoAsset;
import com.expnai.relay.domain.entity.RelayServiceConnection;
import com.expnai.relay.domain.enums.RelayPromoAssetStatus;
import com.expnai.relay.domain.enums.RelayServiceStatus;
import com.expnai.relay.domain.repository.RelayPromoAssetRepository;
import com.expnai.relay.domain.repository.RelayServiceConnectionRepository;
import com.expnai.relay.features.reach.ReachPublishClient;
import com.expnai.relay.features.reach.ReachTokenStore;
import com.expnai.relay.features.reach.ReachTokenStore.Tokens;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * Posts at most one due scheduled Short per tick to each connected reach platform.
 * Nothing posts until {@code promote/schedule} sets {@code scheduledFor}.
 */
@Service
@RequiredArgsConstructor
public class PromoteScheduleWorker {

	private static final Logger log = LoggerFactory.getLogger(PromoteScheduleWorker.class);
	private static final List<String> PLATFORMS = List.of("youtube", "tiktok", "instagram");

	private final RelayPromoAssetRepository promoAssetRepository;
	private final RelayServiceConnectionRepository connectionRepository;
	private final ReachTokenStore tokenStore;
	private final ReachPublishClient publishClient;
	private final ObjectMapper objectMapper;
	private final TransactionTemplate transactionTemplate;

	@Scheduled(fixedDelayString = "${relay.reach.schedule-poll-ms:60000}")
	public void tick() {
		List<RelayPromoAsset> due = promoAssetRepository.findDueScheduledPosts(
				RelayPromoAssetStatus.SCHEDULED, Instant.now());
		if (due.isEmpty()) {
			return;
		}
		// One Short per tick keeps the one/day cadence honest even if the poller is late.
		RelayPromoAsset asset = due.get(0);
		transactionTemplate.executeWithoutResult(status -> postAsset(asset.getId()));
	}

	private void postAsset(Long assetId) {
		RelayPromoAsset asset = promoAssetRepository.findById(assetId).orElse(null);
		if (asset == null || asset.getPostedAt() != null || asset.getUrl() == null) {
			return;
		}
		if (!asset.getUrl().startsWith("http://") && !asset.getUrl().startsWith("https://")) {
			log.warn("Promo asset {} has no https video URL yet; skipping post", assetId);
			return;
		}
		Long userId = asset.getProduct() != null && asset.getProduct().getUser() != null
				? asset.getProduct().getUser().getId()
				: null;
		if (userId == null) {
			return;
		}
		Map<String, Object> results = readResults(asset.getPostResultsJson());
		String title = asset.getTitle() != null ? asset.getTitle() : "Relay Short";
		String caption = title + "\n\nutm_source=relay&utm_medium=promote&utm_campaign=schedule";
		boolean anySuccess = false;

		for (String platform : PLATFORMS) {
			if (results.containsKey(platform) && !String.valueOf(results.get(platform)).startsWith("err:")) {
				anySuccess = true;
				continue;
			}
			var connOpt = connectionRepository
					.findFirstByUserIdAndServiceIdIgnoreCaseAndStatusOrderByCreatedAtDesc(
							userId, platform, RelayServiceStatus.CONNECTED);
			if (connOpt.isEmpty()) {
				continue;
			}
			RelayServiceConnection conn = connOpt.get();
			try {
				Tokens tokens = tokenStore.require(conn);
				String externalId = switch (platform) {
					case "youtube" -> publishClient.publishYouTube(tokens, title, caption, asset.getUrl());
					case "tiktok" -> publishClient.publishTikTok(tokens, title, asset.getUrl());
					case "instagram" -> publishClient.publishInstagram(tokens, caption, asset.getUrl());
					default -> null;
				};
				results.put(platform, externalId != null ? externalId : "ok");
				anySuccess = true;
				log.info("Posted promo asset {} to {} as {}", assetId, platform, externalId);
			}
			catch (Exception ex) {
				results.put(platform, "err:" + truncate(ex.getMessage(), 180));
				log.warn("Post promo asset {} to {} failed: {}", assetId, platform, ex.getMessage());
			}
		}

		asset.setPostResultsJson(writeResults(results));
		if (anySuccess) {
			asset.setPostedAt(Instant.now());
		}
		promoAssetRepository.save(asset);
	}

	private Map<String, Object> readResults(String json) {
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

	private String writeResults(Map<String, Object> map) {
		try {
			return objectMapper.writeValueAsString(map);
		}
		catch (Exception ex) {
			return "{}";
		}
	}

	private static String truncate(String value, int max) {
		if (value == null) {
			return "";
		}
		return value.length() <= max ? value : value.substring(0, max) + "…";
	}
}
