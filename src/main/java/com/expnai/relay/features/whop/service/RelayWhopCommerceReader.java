package com.expnai.relay.features.whop.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.expnai.relay.domain.entity.RelayListing;
import com.expnai.relay.domain.entity.RelayOrder;
import com.expnai.relay.domain.entity.RelayWhopProduct;
import com.expnai.relay.domain.enums.RelayListingStatus;
import com.expnai.relay.domain.repository.RelayListingRepository;
import com.expnai.relay.domain.repository.RelayOrderRepository;
import com.expnai.relay.domain.repository.RelayWhopProductRepository;
import com.expnai.relay.features.product.dto.response.RelayProductResponse;
import com.expnai.relay.features.product.dto.response.RelayProductResponse.MetricsDto;
import com.expnai.relay.features.product.dto.response.RelayProductResponse.OrderDto;
import com.expnai.relay.features.product.dto.response.RelayProductResponse.PublishStateDto;

import lombok.RequiredArgsConstructor;

/** Adds Whop listing state, orders and sales metrics to a product response. */
@Component
@RequiredArgsConstructor
public class RelayWhopCommerceReader {

	public static final String PLATFORM = "whop";

	private final RelayWhopProductRepository whopProductRepository;
	private final RelayListingRepository listingRepository;
	private final RelayOrderRepository orderRepository;

	@Transactional(readOnly = true)
	public RelayProductResponse enrich(RelayProductResponse response, Long productId) {
		RelayWhopProduct whop = whopProductRepository.findByProductId(productId).orElse(null);
		List<RelayOrder> orders = orderRepository.findByProductIdOrderByOrderedAtDesc(productId);
		boolean live = whop != null && whop.getCheckoutUrl() != null;
		if (!live && orders.isEmpty()) {
			return response;
		}

		Map<String, String> liveUrls = new LinkedHashMap<>();
		Map<String, Object> listings = new LinkedHashMap<>();
		if (live) {
			liveUrls.put(PLATFORM, whop.getCheckoutUrl());
			RelayListing listing = listingRepository.findFirstByProductIdAndPlatformIgnoreCase(productId, PLATFORM)
					.orElse(null);
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("url", whop.getCheckoutUrl());
			if (listing != null) {
				entry.put("title", listing.getTitle());
				entry.put("price", listing.getPrice());
				entry.put("status", listing.getStatus() != null
						? listing.getStatus().getCode().toLowerCase()
						: RelayListingStatus.LIVE.getCode().toLowerCase());
			}
			listings.put(PLATFORM, entry);
		}
		PublishStateDto publish = new PublishStateDto(
				live ? List.of(PLATFORM) : List.of(),
				listings,
				liveUrls,
				null,
				live ? "live" : response.publish().status(),
				null);

		List<Object> orderDtos = orders.stream()
				.<Object>map(o -> new OrderDto(
						o.getOrderedAt().toString(),
						o.getAmount().doubleValue(),
						sourceLabel(o.getSource())))
				.toList();
		BigDecimal revenue = orders.stream().map(RelayOrder::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
		MetricsDto metrics = new MetricsDto(orders.size(), revenue.doubleValue(), 0, 0);
		return response.withCommerce(orderDtos, publish, metrics);
	}

	private static String sourceLabel(String source) {
		return source == null || source.isBlank() || PLATFORM.equalsIgnoreCase(source) ? "Whop checkout" : source;
	}
}
