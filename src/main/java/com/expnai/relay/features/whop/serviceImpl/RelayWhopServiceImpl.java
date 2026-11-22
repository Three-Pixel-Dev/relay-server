package com.expnai.relay.features.whop.serviceImpl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.expnai.relay.common.exception.BusinessRuleException;
import com.expnai.relay.common.exception.InvalidSessionException;
import com.expnai.relay.common.exception.ResourceConflictException;
import com.expnai.relay.common.exception.ResourceNotFoundException;
import com.expnai.relay.common.security.CredentialEncryptionService;
import com.expnai.relay.common.web.ApiResponse;
import com.expnai.relay.domain.entity.RelayAuditEvent;
import com.expnai.relay.domain.entity.RelayBrand;
import com.expnai.relay.domain.entity.RelayListing;
import com.expnai.relay.domain.entity.RelayPartnership;
import com.expnai.relay.domain.entity.RelayProduct;
import com.expnai.relay.domain.entity.RelayServiceConnection;
import com.expnai.relay.domain.entity.RelayThreadMessage;
import com.expnai.relay.domain.entity.RelayWhopProduct;
import com.expnai.relay.domain.enums.RelayListingStatus;
import com.expnai.relay.domain.enums.RelayServiceStatus;
import com.expnai.relay.domain.enums.RelayThreadWho;
import com.expnai.relay.domain.repository.RelayAuditEventRepository;
import com.expnai.relay.domain.repository.RelayBrandRepository;
import com.expnai.relay.domain.repository.RelayListingRepository;
import com.expnai.relay.domain.repository.RelayPartnershipRepository;
import com.expnai.relay.domain.repository.RelayProductFileRepository;
import com.expnai.relay.domain.repository.RelayProductRepository;
import com.expnai.relay.domain.repository.RelayServiceConnectionRepository;
import com.expnai.relay.domain.repository.RelayThreadMessageRepository;
import com.expnai.relay.domain.repository.RelayWhopProductRepository;
import com.expnai.relay.features.auth.security.RelayUserPrincipal;
import com.expnai.relay.features.product.dto.response.RelayProductResponse;
import com.expnai.relay.features.product.service.RelayProductService;
import com.expnai.relay.features.whop.client.WhopApiClient;
import com.expnai.relay.features.whop.client.WhopApiClient.WhopAccount;
import com.expnai.relay.features.whop.client.WhopApiClient.WhopPlan;
import com.expnai.relay.features.whop.client.WhopApiClient.WhopProduct;
import com.expnai.relay.features.whop.client.WhopApiClient.WhopWebhook;
import com.expnai.relay.features.whop.dto.request.RelayWhopPublishRequest;
import com.expnai.relay.features.whop.service.RelayWhopCommerceReader;
import com.expnai.relay.features.whop.service.RelayWhopService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RelayWhopServiceImpl implements RelayWhopService {

	private static final Logger log = LoggerFactory.getLogger(RelayWhopServiceImpl.class);
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final String SERVICE_ID = RelayWhopCommerceReader.PLATFORM;
	private static final List<String> WEBHOOK_EVENTS = List.of("payment.succeeded");

	private final WhopApiClient whopApiClient;
	private final CredentialEncryptionService credentialEncryptionService;
	private final RelayServiceConnectionRepository connectionRepository;
	private final RelayProductRepository productRepository;
	private final RelayProductFileRepository productFileRepository;
	private final RelayBrandRepository brandRepository;
	private final RelayWhopProductRepository whopProductRepository;
	private final RelayListingRepository listingRepository;
	private final RelayPartnershipRepository partnershipRepository;
	private final RelayThreadMessageRepository threadMessageRepository;
	private final RelayAuditEventRepository auditEventRepository;
	private final RelayProductService productService;
	private final TransactionTemplate transactionTemplate;

	@Value("${relay.public-api-url:}")
	private String publicApiUrl;

	@Override
	public void connect(RelayServiceConnection connection, String apiKey) {
		String key = apiKey == null ? "" : apiKey.trim();
		if (key.length() < 8) {
			throw new BusinessRuleException("Paste your Whop API key.");
		}
		String previousKey = decryptQuietly(connection.getEncryptedCredential());
		WhopAccount account = whopApiClient.getCurrentAccount(key);

		String baseUrl = publicBaseUrl();
		if (baseUrl != null) {
			if (connection.getWebhookExternalId() != null && previousKey != null) {
				deleteWebhookQuietly(previousKey, connection.getWebhookExternalId());
			}
			String token = randomToken();
			WhopWebhook webhook = whopApiClient.createWebhook(
					key, baseUrl + "/api/webhooks/whop/" + token, "Relay sales", WEBHOOK_EVENTS);
			connection.setWebhookToken(token);
			connection.setWebhookExternalId(webhook.id());
			connection.setEncryptedWebhookSecret(credentialEncryptionService.encrypt(webhook.secret()));
		}
		else {
			log.warn("RELAY_PUBLIC_API_URL is not set; Whop sales webhook was not registered");
		}

		connection.setEncryptedCredential(credentialEncryptionService.encrypt(key));
		connection.setExternalAccountId(account.id());
		connection.setLabel(account.title() != null ? account.title() : "Connected");
		connection.setStatus(RelayServiceStatus.CONNECTED);
	}

	@Override
	public void disconnect(RelayServiceConnection connection) {
		String key = decryptQuietly(connection.getEncryptedCredential());
		if (key != null && connection.getWebhookExternalId() != null) {
			deleteWebhookQuietly(key, connection.getWebhookExternalId());
		}
		connection.setWebhookToken(null);
		connection.setWebhookExternalId(null);
		connection.setEncryptedWebhookSecret(null);
		connection.setExternalAccountId(null);
	}

	@Override
	public ApiResponse<RelayProductResponse> publish(Long productId, RelayWhopPublishRequest request) {
		Long userId = currentUserId();
		PublishPlan plan;
		try {
			plan = transactionTemplate.execute(status -> prepare(userId, productId, request));
		}
		catch (DataIntegrityViolationException ex) {
			throw new ResourceConflictException("Publishing to Whop is already in progress for this product.");
		}
		if (plan == null) {
			throw new IllegalStateException("Publish preparation returned nothing");
		}
		if (plan.alreadyLive()) {
			return productService.getProduct(productId);
		}

		String externalProductId = plan.externalProductId();
		if (externalProductId == null) {
			String redirect = publicBaseUrl() + "/api/public/deliveries/" + plan.deliveryToken();
			WhopProduct created = whopApiClient.createProduct(
					plan.apiKey(), plan.title(), plan.headline(), redirect, plan.affiliatePercent());
			externalProductId = created.id();
			String savedProductId = externalProductId;
			transactionTemplate.executeWithoutResult(status -> {
				RelayWhopProduct row = whopProductRepository.findById(plan.whopRowId()).orElseThrow();
				row.setExternalProductId(savedProductId);
				whopProductRepository.save(row);
			});
		}

		WhopPlan whopPlan = whopApiClient.createPlan(
				plan.apiKey(), externalProductId, plan.title(), plan.price(), plan.currency());
		transactionTemplate.executeWithoutResult(status -> complete(plan, whopPlan));
		return productService.getProduct(productId);
	}

	private PublishPlan prepare(Long userId, Long productId, RelayWhopPublishRequest request) {
		RelayProduct product = productRepository.findByIdAndUserId(productId, userId)
				.orElseThrow(() -> new ResourceNotFoundException("Product not found"));
		if (productFileRepository.findByProductIdOrderByIdAsc(productId).isEmpty()) {
			throw new BusinessRuleException("Finish the Ready step first. There are no files to sell yet.");
		}
		RelayServiceConnection connection = connectionRepository
				.findFirstByUserIdAndServiceIdIgnoreCaseAndStatusOrderByCreatedAtDesc(
						userId, SERVICE_ID, RelayServiceStatus.CONNECTED)
				.orElseThrow(() -> new BusinessRuleException("Connect Whop in Connections first."));
		String apiKey = decryptQuietly(connection.getEncryptedCredential());
		if (apiKey == null) {
			throw new BusinessRuleException("Your Whop connection has no API key. Reconnect Whop in Connections.");
		}
		if (publicBaseUrl() == null) {
			throw new BusinessRuleException(
					"Publishing is not configured on this server yet (public API URL is missing).");
		}

		RelayWhopProduct existing = whopProductRepository.findByProductId(productId).orElse(null);
		String title = resolveTitle(request, product);
		BigDecimal price = request.getPrice().setScale(2, RoundingMode.HALF_UP);
		String currency = request.getCurrency() == null || request.getCurrency().isBlank()
				? "USD"
				: request.getCurrency().trim().toUpperCase(Locale.ROOT);
		if (currency.length() != 3) {
			throw new BusinessRuleException("Currency must be a 3-letter code such as USD.");
		}

		RelayWhopProduct row = existing;
		if (row != null && row.getCheckoutUrl() != null) {
			return PublishPlan.live(row.getId());
		}
		if (row == null) {
			row = new RelayWhopProduct();
			row.setProduct(product);
			row.setDeliveryToken(randomToken());
			row.setExternalCompanyId(connection.getExternalAccountId());
			row = whopProductRepository.saveAndFlush(row);
		}
		BigDecimal affiliate = request.getAffiliatePercent();
		if (affiliate != null) {
			row.setAffiliateRate(affiliate);
		}
		RelayPartnership partnership = partnershipRepository.findFirstByProductId(productId).orElse(null);
		if (partnership != null) {
			row.setPartnerShare(BigDecimal.valueOf(partnership.getSplitPercent()));
		}
		whopProductRepository.save(row);

		return new PublishPlan(
				false,
				row.getId(),
				apiKey,
				row.getExternalProductId(),
				row.getDeliveryToken(),
				title,
				request.getHeadline(),
				price,
				currency,
				affiliate);
	}

	private void complete(PublishPlan plan, WhopPlan whopPlan) {
		RelayWhopProduct row = whopProductRepository.findById(plan.whopRowId()).orElseThrow();
		RelayProduct product = row.getProduct();
		row.setExternalPlanId(whopPlan.id());
		row.setCheckoutUrl(whopPlan.purchaseUrl());
		whopProductRepository.save(row);

		RelayListing listing = listingRepository
				.findFirstByProductIdAndPlatformIgnoreCase(product.getId(), SERVICE_ID)
				.orElseGet(RelayListing::new);
		listing.setProduct(product);
		listing.setPlatform(SERVICE_ID);
		listing.setTitle(plan.title());
		listing.setPrice(plan.price());
		listing.setStatus(RelayListingStatus.LIVE);
		listing.setExternalId(row.getExternalProductId());
		listing.setUrl(whopPlan.purchaseUrl());
		listingRepository.save(listing);

		RelayThreadMessage message = new RelayThreadMessage();
		message.setProduct(product);
		message.setWho(RelayThreadWho.RELAY);
		message.setText("Live on Whop. Checkout link: " + whopPlan.purchaseUrl());
		threadMessageRepository.save(message);

		RelayAuditEvent audit = new RelayAuditEvent();
		audit.setUser(product.getUser());
		audit.setProduct(product);
		audit.setAction("WHOP_PUBLISH");
		audit.setDetail("Published to Whop product " + row.getExternalProductId() + " at "
				+ plan.price() + " " + plan.currency());
		auditEventRepository.save(audit);
	}

	private String resolveTitle(RelayWhopPublishRequest request, RelayProduct product) {
		String title = request.getTitle();
		if (title == null || title.isBlank()) {
			title = brandRepository.findByProductId(product.getId())
					.map(RelayBrand::getTitle)
					.filter(t -> t != null && !t.isBlank())
					.orElse(product.getName());
		}
		if (title == null || title.isBlank()) {
			title = "Relay product";
		}
		title = title.trim();
		return title.length() > 80 ? title.substring(0, 80) : title;
	}

	private String publicBaseUrl() {
		String url = publicApiUrl == null ? "" : publicApiUrl.trim();
		if (url.isEmpty() || !url.startsWith("http")) {
			return null;
		}
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

	private String decryptQuietly(String stored) {
		if (stored == null || stored.isBlank()) {
			return null;
		}
		try {
			String value = credentialEncryptionService.decrypt(stored);
			return value == null || value.isBlank() ? null : value.trim();
		}
		catch (RuntimeException ex) {
			log.warn("Could not decrypt stored Whop credential");
			return null;
		}
	}

	private void deleteWebhookQuietly(String apiKey, String webhookId) {
		try {
			whopApiClient.deleteWebhook(apiKey, webhookId);
		}
		catch (RuntimeException ex) {
			log.warn("Could not remove previous Whop webhook {}: {}", webhookId, ex.getMessage());
		}
	}

	private static String randomToken() {
		byte[] bytes = new byte[24];
		RANDOM.nextBytes(bytes);
		return HexFormat.of().formatHex(bytes);
	}

	private static Long currentUserId() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof RelayUserPrincipal principal)) {
			throw new InvalidSessionException("Not authenticated");
		}
		return principal.getId();
	}

	private record PublishPlan(
			boolean alreadyLive,
			Long whopRowId,
			String apiKey,
			String externalProductId,
			String deliveryToken,
			String title,
			String headline,
			BigDecimal price,
			String currency,
			BigDecimal affiliatePercent) {

		static PublishPlan live(Long whopRowId) {
			return new PublishPlan(true, whopRowId, null, null, null, null, null, null, null, null);
		}
	}
}
