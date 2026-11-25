package com.expnai.relay.features.product.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.expnai.relay.domain.entity.RelayChannel;
import com.expnai.relay.domain.entity.RelayMetric;
import com.expnai.relay.domain.entity.RelayPromoAsset;
import com.expnai.relay.domain.enums.RelayPromoAssetStatus;
import com.expnai.relay.domain.enums.RelayPromoAssetType;
import com.expnai.relay.domain.repository.RelayChannelRepository;
import com.expnai.relay.domain.repository.RelayMetricRepository;
import com.expnai.relay.domain.repository.RelayPromoAssetRepository;
import com.expnai.relay.features.product.dto.response.RelayProductResponse;
import com.expnai.relay.features.product.dto.response.RelayProductResponse.ChannelStateDto;
import com.expnai.relay.features.product.dto.response.RelayProductResponse.PromoteStateDto;
import com.expnai.relay.features.product.dto.response.RelayProductResponse.PromoVideoDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * Fills product {@code distribute} / {@code promote} maps and metrics leads/visits
 * from persisted channel, promo-asset, and metric rows (zeros when none — never invented).
 */
@Component
@RequiredArgsConstructor
public class RelayDistributePromoteReader {

	private final RelayChannelRepository channelRepository;
	private final RelayPromoAssetRepository promoAssetRepository;
	private final RelayMetricRepository metricRepository;
	private final ObjectMapper objectMapper;

	@Transactional(readOnly = true)
	public RelayProductResponse enrich(RelayProductResponse response, Long productId) {
		Map<String, ChannelStateDto> distribute = toDistribute(
				channelRepository.findByProductIdOrderByIdAsc(productId));
		PromoteStateDto promote = toPromote(
				promoAssetRepository.findByProductIdAndAssetTypeOrderByIdAsc(productId, RelayPromoAssetType.VIDEO));
		int[] leadVisit = sumLeadsVisits(metricRepository.findByProductId(productId));
		return response.withDistributePromote(distribute, promote, leadVisit[0], leadVisit[1]);
	}

	private Map<String, ChannelStateDto> toDistribute(List<RelayChannel> channels) {
		Map<String, ChannelStateDto> map = new LinkedHashMap<>();
		for (RelayChannel channel : channels) {
			if (channel.getChannelType() == null || channel.getChannelType().isBlank()) {
				continue;
			}
			String key = channel.getChannelType().trim().toLowerCase(Locale.ROOT);
			boolean setting = isSettingUp(channel.getSettingsJson());
			map.put(key, new ChannelStateDto(
					channel.isEnabled(),
					setting ? Boolean.TRUE : null,
					channel.getWeekNumber(),
					channel.getClicks() != null ? channel.getClicks() : (channel.isEnabled() ? 0 : null)));
		}
		return map;
	}

	private boolean isSettingUp(String settingsJson) {
		if (settingsJson == null || settingsJson.isBlank()) {
			return false;
		}
		try {
			Map<String, Object> parsed = objectMapper.readValue(
					settingsJson, new TypeReference<Map<String, Object>>() {
					});
			if (parsed == null) {
				return false;
			}
			Object value = parsed.get("setting");
			if (value == null) {
				value = parsed.get("settingUp");
			}
			return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
		}
		catch (Exception ignored) {
			return false;
		}
	}

	private PromoteStateDto toPromote(List<RelayPromoAsset> videos) {
		if (videos == null || videos.isEmpty()) {
			return new PromoteStateDto(List.of(), "todo", false);
		}
		List<PromoVideoDto> videoDtos = videos.stream()
				.map(asset -> new PromoVideoDto(
						asset.getTitle() != null ? asset.getTitle() : "",
						asset.getDuration() != null ? asset.getDuration() : "",
						videoStatus(asset.getStatus())))
				.toList();
		boolean scheduled = videos.stream().anyMatch(a -> a.getStatus() == RelayPromoAssetStatus.SCHEDULED);
		return new PromoteStateDto(videoDtos, promoteStatus(videos), scheduled);
	}

	private static String videoStatus(RelayPromoAssetStatus status) {
		if (status == null) {
			return "todo";
		}
		if (status == RelayPromoAssetStatus.GATE || status == RelayPromoAssetStatus.REVIEW) {
			return "review";
		}
		return status.getCode().toLowerCase(Locale.ROOT);
	}

	private static String promoteStatus(List<RelayPromoAsset> videos) {
		if (videos.stream().anyMatch(a -> a.getStatus() == RelayPromoAssetStatus.RUNNING)) {
			return "running";
		}
		if (videos.stream().anyMatch(a -> a.getStatus() == RelayPromoAssetStatus.GATE
				|| a.getStatus() == RelayPromoAssetStatus.REVIEW)) {
			return "gate";
		}
		if (videos.stream().allMatch(a -> a.getStatus() == null || a.getStatus() == RelayPromoAssetStatus.TODO)) {
			return "todo";
		}
		return "done";
	}

	/** Returns [leads, visits] summed from metric rows; never invents non-zero values. */
	private static int[] sumLeadsVisits(List<RelayMetric> metrics) {
		int leads = 0;
		int visits = 0;
		if (metrics != null) {
			for (RelayMetric metric : metrics) {
				if (metric.getLeads() != null) {
					leads += metric.getLeads();
				}
				if (metric.getVisits() != null) {
					visits += metric.getVisits();
				}
			}
		}
		return new int[] { leads, visits };
	}
}
