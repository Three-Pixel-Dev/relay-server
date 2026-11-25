package com.expnai.relay.features.distribute.setup;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.expnai.relay.domain.entity.RelayBrand;
import com.expnai.relay.domain.entity.RelayChannel;
import com.expnai.relay.domain.entity.RelayProduct;
import com.expnai.relay.domain.entity.RelayWhopProduct;
import com.expnai.relay.domain.repository.RelayBrandRepository;
import com.expnai.relay.domain.repository.RelayWhopProductRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * Phase A setup stubs: durable state only, no fake metrics, no remote posts.
 * Real outbound workers land in a later PR.
 */
@Component
@RequiredArgsConstructor
public class DistributeChannelSetupStub {

	private static final Logger log = LoggerFactory.getLogger(DistributeChannelSetupStub.class);
	private static final BigDecimal DEFAULT_AFFILIATE_RATE = BigDecimal.valueOf(30);

	private final RelayBrandRepository brandRepository;
	private final RelayWhopProductRepository whopProductRepository;
	private final ObjectMapper objectMapper;

	public void setup(RelayProduct product, RelayChannel channel) {
		String type = channel.getChannelType() != null
				? channel.getChannelType().toLowerCase(Locale.ROOT)
				: "";
		Map<String, Object> settings = new LinkedHashMap<>();
		settings.put("setupAt", Instant.now().toString());
		switch (type) {
			case "seo" -> setupSeo(product, settings);
			case "relay" -> setupRelay(product, settings);
			case "affiliates" -> setupAffiliates(product, settings);
			default -> settings.put("note", "Channel enabled; outbound worker not implemented yet.");
		}
		channel.setSettingsJson(writeJson(settings));
		log.info("Distribute setup stub for product {} channel {}", product.getId(), type);
	}

	/**
	 * Builds a queued (not posted) week batch payload with UTM placeholders.
	 * Never marks remote posts as done.
	 */
	public String buildWeekItemsJson(RelayChannel channel, int weekNumber) {
		String type = channel.getChannelType() != null
				? channel.getChannelType().toLowerCase(Locale.ROOT)
				: "unknown";
		String utm = "utm_source=relay&utm_medium=" + type + "&utm_campaign=week-" + weekNumber;
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("channel", type);
		payload.put("week", weekNumber);
		payload.put("status", "queued");
		payload.put("posted", false);
		payload.put("utm", utm);
		payload.put("actions", switch (type) {
			case "seo" -> List.of(Map.of(
					"type", "title_ab_test",
					"status", "queued",
					"note", "Apply listing title variant after 14d window (stub)"));
			case "relay" -> List.of(Map.of(
					"type", "discovery_feature",
					"status", "queued",
					"note", "Include in weekly discovery issue (stub)"));
			case "affiliates" -> List.of(Map.of(
					"type", "affiliate_pitch",
					"status", "queued",
					"note", "Keep affiliate programme live; pitch copy ready (stub)"));
			default -> List.of(Map.of(
					"type", "noop",
					"status", "queued",
					"note", "No outbound worker for this channel yet"));
		});
		payload.put("note", "Approved; nothing posts until a real worker runs.");
		return writeJson(payload);
	}

	private void setupSeo(RelayProduct product, Map<String, Object> settings) {
		String baseTitle = brandRepository.findByProductId(product.getId())
				.map(RelayBrand::getTitle)
				.filter(t -> t != null && !t.isBlank())
				.orElse(product.getName() != null && !product.getName().isBlank() ? product.getName() : "Product");
		String variantB = baseTitle.length() > 70
				? baseTitle.substring(0, 70)
				: baseTitle + " — start here";
		settings.put("status", "ready");
		settings.put("titleVariants", List.of(
				Map.of("id", "A", "title", baseTitle, "active", true),
				Map.of("id", "B", "title", variantB, "active", false)));
		settings.put("windowDays", 14);
		settings.put("note", "Title A/B stored. Winner applies after the test window (stub).");
	}

	private void setupRelay(RelayProduct product, Map<String, Object> settings) {
		settings.put("status", "ready");
		settings.put("discovery", true);
		settings.put("discoveryQueuedAt", Instant.now().toString());
		settings.put("productId", product.getId());
		settings.put("note", "Queued for Relay weekly discovery (internal flag; email blast later).");
	}

	private void setupAffiliates(RelayProduct product, Map<String, Object> settings) {
		RelayWhopProduct whop = whopProductRepository.findByProductId(product.getId()).orElse(null);
		BigDecimal rate = DEFAULT_AFFILIATE_RATE;
		if (whop != null) {
			if (whop.getAffiliateRate() == null || whop.getAffiliateRate().signum() <= 0) {
				whop.setAffiliateRate(DEFAULT_AFFILIATE_RATE);
				whopProductRepository.save(whop);
			}
			else {
				rate = whop.getAffiliateRate();
			}
		}
		String title = brandRepository.findByProductId(product.getId())
				.map(RelayBrand::getTitle)
				.filter(t -> t != null && !t.isBlank())
				.orElse(product.getName() != null ? product.getName() : "this product");
		String pitch = "I made " + title
				+ " for people who need a clear next step. Affiliates earn "
				+ rate.stripTrailingZeros().toPlainString()
				+ "% on every sale via Whop. Happy to send a one-pager and checkout link.";
		settings.put("status", "ready");
		settings.put("affiliateRate", rate);
		settings.put("pitch", pitch);
		settings.put("note", whop != null && whop.getCheckoutUrl() != null
				? "Affiliate rate set on Whop product; pitch ready."
				: "Pitch ready. Publish to Whop so the affiliate rate can apply on checkout.");
	}

	private String writeJson(Map<String, Object> map) {
		try {
			return objectMapper.writeValueAsString(map);
		}
		catch (Exception ex) {
			return "{}";
		}
	}
}
