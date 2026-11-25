package com.expnai.relay.features.distribute.setup;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.expnai.relay.common.exception.BusinessRuleException;
import com.expnai.relay.common.security.CredentialEncryptionService;
import com.expnai.relay.domain.entity.RelayBrand;
import com.expnai.relay.domain.entity.RelayChannel;
import com.expnai.relay.domain.entity.RelayProduct;
import com.expnai.relay.domain.entity.RelayServiceConnection;
import com.expnai.relay.domain.entity.RelayWhopProduct;
import com.expnai.relay.domain.enums.RelayServiceStatus;
import com.expnai.relay.domain.repository.RelayBrandRepository;
import com.expnai.relay.domain.repository.RelayServiceConnectionRepository;
import com.expnai.relay.domain.repository.RelayWhopProductRepository;
import com.expnai.relay.features.beehiiv.BeehiivClient;
import com.expnai.relay.features.pinterest.PinterestApiClient;
import com.expnai.relay.features.pinterest.PinterestApiClient.AdAccount;
import com.expnai.relay.features.pinterest.PinterestApiClient.Board;
import com.expnai.relay.features.pinterest.PinterestTokenService;
import com.expnai.relay.features.pinterest.config.PinterestProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * Channel setup: seo/relay/affiliates stay local; pinterest/leads/paid call real APIs when connected.
 */
@Component
@RequiredArgsConstructor
public class DistributeChannelSetupStub {

	private static final Logger log = LoggerFactory.getLogger(DistributeChannelSetupStub.class);
	private static final BigDecimal DEFAULT_AFFILIATE_RATE = BigDecimal.valueOf(30);
	private static final List<String> BOARD_NAMES = List.of(
			"Product pages",
			"Tips & quotes",
			"Before / after",
			"Lead magnet");

	private final RelayBrandRepository brandRepository;
	private final RelayWhopProductRepository whopProductRepository;
	private final RelayServiceConnectionRepository connectionRepository;
	private final CredentialEncryptionService credentialEncryptionService;
	private final PinterestTokenService pinterestTokenService;
	private final PinterestApiClient pinterestApiClient;
	private final PinterestProperties pinterestProperties;
	private final BeehiivClient beehiivClient;
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
			case "pinterest" -> setupPinterest(product, settings);
			case "leads" -> setupLeads(product, settings);
			case "paid" -> setupPaid(product, settings);
			default -> settings.put("note", "Channel enabled; outbound worker not implemented yet.");
		}
		channel.setSettingsJson(writeJson(settings));
		log.info("Distribute setup for product {} channel {}", product.getId(), type);
	}

	/**
	 * Builds a queued week batch payload with UTM on every outbound link.
	 * Nothing is marked posted here — workers may post after approve.
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
			case "pinterest" -> buildPinterestWeekActions(channel, weekNumber, utm);
			case "leads" -> List.of(Map.of(
					"type", "lead_sequence",
					"status", "queued",
					"utm", utm,
					"note", "Lead magnet + 5-email sequence tracked in beehiiv; no fake open rates"));
			case "paid" -> List.of(Map.of(
					"type", "paid_budget_check",
					"status", "queued",
					"dailyUsd", 5,
					"hardStopUsd", 50,
					"note", "Enforce $5/day and $50 lifetime hard stop"));
			default -> List.of(Map.of(
					"type", "noop",
					"status", "queued",
					"note", "No outbound worker for this channel yet"));
		});
		payload.put("note", "Approved; posts only via workers with UTM.");
		return writeJson(payload);
	}

	private List<Map<String, Object>> buildPinterestWeekActions(
			RelayChannel channel, int weekNumber, String utm) {
		// 2 pins/day × 7 days = 14 pins per approved week (of the 40-pin plan).
		List<Map<String, Object>> actions = new ArrayList<>();
		for (int i = 0; i < 14; i++) {
			Map<String, Object> action = new LinkedHashMap<>();
			action.put("type", "pin");
			action.put("day", (i / 2) + 1);
			action.put("index", i);
			action.put("status", "queued");
			action.put("utm", utm);
			action.put("week", weekNumber);
			actions.add(action);
		}
		return actions;
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

	private void setupPinterest(RelayProduct product, Map<String, Object> settings) {
		RelayServiceConnection conn = requireConnection(product.getUser().getId(), "pinterest");
		String access = pinterestTokenService.requireAccessToken(conn);
		List<Board> existing = pinterestApiClient.listBoards(access);
		List<Map<String, String>> boards = new ArrayList<>();
		for (String name : BOARD_NAMES) {
			Optional<Board> match = existing.stream()
					.filter(b -> name.equalsIgnoreCase(b.name()))
					.findFirst();
			Board board = match.orElseGet(() -> pinterestApiClient.createBoard(
					access, name, "Relay distribute boards for " + productLabel(product)));
			boards.add(Map.of("id", board.id() != null ? board.id() : "", "name", board.name() != null ? board.name() : name));
		}
		settings.put("status", "ready");
		settings.put("boards", boards);
		settings.put("pinPlan", Map.of(
				"total", 40,
				"perDay", 2,
				"days", 60));
		settings.put("note", "4 boards ready. Weekly approve queues 14 pins (2/day); posts need a public image URL + UTM link.");
	}

	private void setupLeads(RelayProduct product, Map<String, Object> settings) {
		RelayServiceConnection conn = requireConnection(product.getUser().getId(), "beehiiv");
		String apiKey = decryptCredential(conn);
		var pubs = beehiivClient.listPublications(apiKey);
		if (pubs.isEmpty()) {
			throw new BusinessRuleException(
					"No beehiiv publications on this key. Create a publication in beehiiv first.");
		}
		var pub = pubs.get(0);
		if (conn.getExternalAccountId() == null || conn.getExternalAccountId().isBlank()) {
			conn.setExternalAccountId(pub.id());
			connectionRepository.save(conn);
		}
		String pubId = conn.getExternalAccountId() != null ? conn.getExternalAccountId() : pub.id();
		Map<String, Object> summary = beehiivClient.publicationSummary(apiKey, pubId);
		String checkout = whopProductRepository.findByProductId(product.getId())
				.map(RelayWhopProduct::getCheckoutUrl)
				.filter(u -> u != null && !u.isBlank())
				.orElse(null);
		settings.put("status", "ready");
		settings.put("publicationId", pubId);
		settings.put("publication", summary);
		settings.put("leadMagnet", Map.of(
				"offer", "Free sample from " + productLabel(product),
				"sequenceEmails", 5,
				"cta", checkout != null ? checkout : "product checkout",
				"utm", "utm_source=relay&utm_medium=leads&utm_campaign=lead-magnet"));
		settings.put("note",
				"beehiiv publication linked. Sequence + landing live in beehiiv; Relay does not invent open/click metrics.");
	}

	private void setupPaid(RelayProduct product, Map<String, Object> settings) {
		RelayServiceConnection conn = requireConnection(product.getUser().getId(), "pinterest");
		String access = pinterestTokenService.requireAccessToken(conn);
		long daily = pinterestProperties.getPaidDailyBudgetMicros();
		long lifetime = pinterestProperties.getPaidLifetimeBudgetMicros();
		List<AdAccount> accounts = pinterestApiClient.listAdAccounts(access);
		String adAccountId = accounts.isEmpty() ? null : accounts.get(0).id();
		String campaignId = null;
		if (adAccountId != null) {
			campaignId = pinterestApiClient.createCappedCampaign(
					access,
					adAccountId,
					"Relay paid test — " + productLabel(product),
					daily,
					lifetime);
		}
		settings.put("status", "ready");
		settings.put("dailyUsd", 5);
		settings.put("hardStopUsd", 50);
		settings.put("dailyBudgetMicros", daily);
		settings.put("lifetimeBudgetMicros", lifetime);
		settings.put("adAccountId", adAccountId);
		settings.put("campaignId", campaignId);
		settings.put("campaignStatus", campaignId != null ? "PAUSED" : "LOCAL_ONLY");
		settings.put("spentMicros", 0);
		settings.put("hardStopped", false);
		settings.put("note", campaignId != null
				? "Pinterest campaign created PAUSED with $5/day and $50 lifetime caps. Enable in Ads Manager when ready; Relay pauses at $50."
				: "Budget caps recorded ($5/day, $50 hard stop). Connect a Pinterest ad account with billing to create the remote campaign.");
	}

	private RelayServiceConnection requireConnection(Long userId, String serviceId) {
		return connectionRepository
				.findFirstByUserIdAndServiceIdIgnoreCaseAndStatusOrderByCreatedAtDesc(
						userId, serviceId, RelayServiceStatus.CONNECTED)
				.orElseThrow(() -> new BusinessRuleException(
						"Connect " + serviceId + " in Connections first."));
	}

	private String decryptCredential(RelayServiceConnection conn) {
		try {
			String key = credentialEncryptionService.decrypt(conn.getEncryptedCredential());
			if (key == null || key.isBlank()) {
				throw new BusinessRuleException("Reconnect beehiiv with a valid API key.");
			}
			return key.trim();
		}
		catch (BusinessRuleException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new BusinessRuleException("Reconnect beehiiv with a valid API key.");
		}
	}

	private static String productLabel(RelayProduct product) {
		return product.getName() != null && !product.getName().isBlank() ? product.getName() : "Product";
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
