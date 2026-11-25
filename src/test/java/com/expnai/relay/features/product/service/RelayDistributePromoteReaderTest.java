package com.expnai.relay.features.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
import com.expnai.relay.features.product.dto.response.RelayProductResponse.MetricsDto;
import com.expnai.relay.features.product.dto.response.RelayProductResponse.PromoteStateDto;
import com.expnai.relay.features.product.dto.response.RelayProductResponse.PublishStateDto;
import com.fasterxml.jackson.databind.ObjectMapper;

class RelayDistributePromoteReaderTest {

	private RelayChannelRepository channels;
	private RelayPromoAssetRepository promoAssets;
	private RelayMetricRepository metrics;
	private RelayDistributePromoteReader reader;

	@BeforeEach
	void setUp() {
		channels = mock(RelayChannelRepository.class);
		promoAssets = mock(RelayPromoAssetRepository.class);
		metrics = mock(RelayMetricRepository.class);
		reader = new RelayDistributePromoteReader(channels, promoAssets, metrics, new ObjectMapper());
	}

	@Test
	void enrichMapsEmptyStateAndZeroLeadVisits() {
		when(channels.findByProductIdOrderByIdAsc(9L)).thenReturn(List.of());
		when(promoAssets.findByProductIdAndAssetTypeOrderByIdAsc(9L, RelayPromoAssetType.VIDEO)).thenReturn(List.of());
		when(metrics.findByProductId(9L)).thenReturn(List.of());

		RelayProductResponse out = reader.enrich(baseResponse(3, 42.5), 9L);

		assertTrue(out.distribute().isEmpty());
		assertEquals("todo", out.promote().status());
		assertTrue(out.promote().videos().isEmpty());
		assertFalse(out.promote().scheduled());
		assertEquals(3, out.metrics().sales());
		assertEquals(42.5, out.metrics().revenue());
		assertEquals(0, out.metrics().leads());
		assertEquals(0, out.metrics().visits());
	}

	@Test
	void enrichMapsChannelsPromoAndMetricSums() {
		RelayChannel seo = new RelayChannel();
		seo.setChannelType("SEO");
		seo.setEnabled(true);
		seo.setWeekNumber(2);
		seo.setClicks(38);

		RelayChannel pins = new RelayChannel();
		pins.setChannelType("pinterest");
		pins.setEnabled(false);
		pins.setSettingsJson("{\"setting\":true}");

		RelayPromoAsset gate = new RelayPromoAsset();
		gate.setAssetType(RelayPromoAssetType.VIDEO);
		gate.setTitle("3 things inside");
		gate.setDuration("0:20");
		gate.setStatus(RelayPromoAssetStatus.GATE);

		RelayMetric day1 = new RelayMetric();
		day1.setLeads(10);
		day1.setVisits(100);
		RelayMetric day2 = new RelayMetric();
		day2.setLeads(5);
		day2.setVisits(null);

		when(channels.findByProductIdOrderByIdAsc(9L)).thenReturn(List.of(seo, pins));
		when(promoAssets.findByProductIdAndAssetTypeOrderByIdAsc(9L, RelayPromoAssetType.VIDEO))
				.thenReturn(List.of(gate));
		when(metrics.findByProductId(9L)).thenReturn(List.of(day1, day2));

		RelayProductResponse out = reader.enrich(baseResponse(1, 9.0), 9L);

		Map<String, ChannelStateDto> distribute = out.distribute();
		assertEquals(2, distribute.size());
		assertEquals(true, distribute.get("seo").on());
		assertEquals(2, distribute.get("seo").week());
		assertEquals(38, distribute.get("seo").clicks());
		assertEquals(false, distribute.get("pinterest").on());
		assertEquals(true, distribute.get("pinterest").setting());

		PromoteStateDto promote = out.promote();
		assertEquals("gate", promote.status());
		assertEquals(1, promote.videos().size());
		assertEquals("3 things inside", promote.videos().get(0).title());
		assertEquals("0:20", promote.videos().get(0).dur());
		assertEquals("review", promote.videos().get(0).status());
		assertFalse(promote.scheduled());

		assertEquals(1, out.metrics().sales());
		assertEquals(9.0, out.metrics().revenue());
		assertEquals(15, out.metrics().leads());
		assertEquals(100, out.metrics().visits());
	}

	private static RelayProductResponse baseResponse(int sales, double revenue) {
		return new RelayProductResponse(
				"9", "n", "", "guided", false, "", null, List.of(), null, List.of(), List.of(), null, null, Map.of(),
				List.of(), 0, null, List.of(), "publish", 7, List.of(), List.of(),
				new PublishStateDto(List.of(), Map.of(), Map.of(), null, "todo", null),
				Map.of(),
				new PromoteStateDto(List.of(), "todo", false),
				new MetricsDto(sales, revenue, 0, 0),
				BigDecimal.ZERO, null, true, null, null, false, "t", "t");
	}
}
