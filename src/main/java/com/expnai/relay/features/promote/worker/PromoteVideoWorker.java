package com.expnai.relay.features.promote.worker;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.expnai.relay.common.logging.RelayTrace;
import com.expnai.relay.common.persistence.AuditUserContext;
import com.expnai.relay.domain.entity.RelayBrand;
import com.expnai.relay.domain.entity.RelayProduct;
import com.expnai.relay.domain.entity.RelayPromoAsset;
import com.expnai.relay.domain.entity.RelayThreadMessage;
import com.expnai.relay.domain.enums.RelayPromoAssetStatus;
import com.expnai.relay.domain.enums.RelayThreadWho;
import com.expnai.relay.domain.repository.RelayBrandRepository;
import com.expnai.relay.domain.repository.RelayProductRepository;
import com.expnai.relay.domain.repository.RelayPromoAssetRepository;
import com.expnai.relay.domain.repository.RelayThreadMessageRepository;
import com.expnai.relay.features.product.sse.ProductSseHub;
import com.expnai.relay.features.promote.higgsfield.HiggsfieldClient;
import com.expnai.relay.features.promote.higgsfield.HiggsfieldClient.Credentials;
import com.expnai.relay.features.promote.higgsfield.HiggsfieldClient.StatusResult;
import com.expnai.relay.features.promote.higgsfield.HiggsfieldClient.SubmitResult;
import com.expnai.relay.features.promote.higgsfield.RelayHiggsfieldCredentialService;

import lombok.RequiredArgsConstructor;

/**
 * Async Higgsfield render for one promo Short. First video → GATE; rest → DONE.
 * Spend is charged only on successful completion (estimate × duration).
 */
@Service
@RequiredArgsConstructor
public class PromoteVideoWorker {

	private static final Logger log = LoggerFactory.getLogger(PromoteVideoWorker.class);
	private static final Duration GENERATION_TIMEOUT = Duration.ofMinutes(12);

	private final RelayProductRepository productRepository;
	private final RelayPromoAssetRepository promoAssetRepository;
	private final RelayBrandRepository brandRepository;
	private final RelayThreadMessageRepository threadMessageRepository;
	private final RelayHiggsfieldCredentialService credentialService;
	private final HiggsfieldClient higgsfieldClient;
	private final ProductSseHub productSseHub;
	private final TransactionTemplate transactionTemplate;

	@Async("relayIngestionTaskExecutor")
	public void generateAsync(Long productId, Long userId, Long assetId, boolean firstVideoGate) {
		AuditUserContext.setCurrentUserId(userId);
		RelayTrace.step("promote-video start product={} asset={}", productId, assetId);
		try {
			Credentials credentials = credentialService.requireCredentials(userId);
			PromptContext ctx = loadPromptContext(productId, assetId);
			if (ctx == null) {
				return;
			}
			SubmitResult submit = higgsfieldClient.submitTextToVideo(
					credentials, ctx.prompt(), HiggsfieldClient.DEFAULT_DURATION_SECONDS);
			transactionTemplate.executeWithoutResult(status -> {
				promoAssetRepository.findById(assetId).ifPresent(asset -> {
					// Stash request id in URL until the real video URL lands.
					asset.setUrl("higgsfield:" + submit.requestId());
					promoAssetRepository.save(asset);
				});
			});
			StatusResult done = higgsfieldClient.waitForCompletion(
					credentials, submit.statusUrl(), GENERATION_TIMEOUT);
			completeSuccess(productId, assetId, done.videoUrl(), firstVideoGate);
			RelayTrace.step("promote-video done product={} asset={}", productId, assetId);
		}
		catch (Exception ex) {
			log.error("Promote video failed product={} asset={}: {}", productId, assetId, ex.getMessage(), ex);
			failAsset(productId, assetId, ex.getMessage() != null
					? ex.getMessage()
					: "Could not generate the promo video. Try again.");
		}
		finally {
			AuditUserContext.clear();
		}
	}

	private PromptContext loadPromptContext(Long productId, Long assetId) {
		return transactionTemplate.execute(status -> {
			RelayProduct product = productRepository.findById(productId).orElse(null);
			RelayPromoAsset asset = promoAssetRepository.findById(assetId).orElse(null);
			if (product == null || asset == null || asset.getStatus() != RelayPromoAssetStatus.RUNNING) {
				return null;
			}
			RelayBrand brand = brandRepository.findByProductId(productId).orElse(null);
			String title = brand != null && brand.getTitle() != null && !brand.getTitle().isBlank()
					? brand.getTitle().trim()
					: (product.getName() != null && !product.getName().isBlank()
							? product.getName().trim()
							: "digital product");
			String clipTitle = asset.getTitle() != null ? asset.getTitle().trim() : "Product flip-through";
			String prompt = buildPrompt(title, clipTitle, product.getInput());
			return new PromptContext(prompt);
		});
	}

	public static String buildPrompt(String productTitle, String clipTitle, String productInput) {
		String topic = productInput != null && !productInput.isBlank()
				? productInput.trim()
				: productTitle;
		if (topic.length() > 280) {
			topic = topic.substring(0, 280) + "…";
		}
		return """
				Vertical 9:16 short-form promo video, about 20 seconds, captions on.
				Product title on screen: "%s".
				Clip theme: "%s".
				Show a clean flip-through of a digital product cover and three interior pages,
				modern editorial look, soft motion, readable text overlays, no watermarks,
				no logos of other brands. Topic: %s.
				""".formatted(productTitle, clipTitle, topic).trim();
	}

	private void completeSuccess(Long productId, Long assetId, String videoUrl, boolean firstVideoGate) {
		transactionTemplate.executeWithoutResult(status -> {
			RelayPromoAsset asset = promoAssetRepository.findById(assetId).orElse(null);
			RelayProduct product = productRepository.findById(productId).orElse(null);
			if (asset == null || product == null) {
				return;
			}
			asset.setUrl(videoUrl);
			asset.setStatus(firstVideoGate ? RelayPromoAssetStatus.GATE : RelayPromoAssetStatus.DONE);
			if (asset.getDuration() == null || asset.getDuration().isBlank()) {
				asset.setDuration(formatDuration(HiggsfieldClient.DEFAULT_DURATION_SECONDS));
			}
			promoAssetRepository.save(asset);

			BigDecimal charge = HiggsfieldClient.USD_PER_SECOND_480P
					.multiply(BigDecimal.valueOf(HiggsfieldClient.DEFAULT_DURATION_SECONDS))
					.setScale(2, RoundingMode.HALF_UP);
			BigDecimal current = product.getSpend() != null ? product.getSpend() : BigDecimal.ZERO;
			product.setSpend(current.add(charge).setScale(6, RoundingMode.HALF_UP));
			productRepository.save(product);

			String note = firstVideoGate
					? "First promo Short is ready for approval."
					: "Promo Short ready: " + (asset.getTitle() != null ? asset.getTitle() : "video");
			saveThread(product, note);
		});
		productSseHub.publish(productId, "promote", java.util.Map.of(
				"assetId", String.valueOf(assetId),
				"status", firstVideoGate ? "gate" : "done"), false);
	}

	private void failAsset(Long productId, Long assetId, String message) {
		transactionTemplate.executeWithoutResult(status -> {
			promoAssetRepository.findById(assetId).ifPresent(asset -> {
				asset.delete();
				promoAssetRepository.save(asset);
			});
			productRepository.findById(productId).ifPresent(product -> saveThread(product, message));
		});
		productSseHub.publish(productId, "promote", java.util.Map.of(
				"assetId", String.valueOf(assetId),
				"status", "failed",
				"message", message), false);
	}

	private void saveThread(RelayProduct product, String text) {
		RelayThreadMessage msg = new RelayThreadMessage();
		msg.setProduct(product);
		msg.setWho(RelayThreadWho.RELAY);
		msg.setText(text);
		threadMessageRepository.save(msg);
	}

	private static String formatDuration(int seconds) {
		int m = seconds / 60;
		int s = seconds % 60;
		return String.format(Locale.ROOT, "%d:%02d", m, s);
	}

	private record PromptContext(String prompt) {
	}

	/** Kick several remaining videos after gate approve. */
	@Async("relayIngestionTaskExecutor")
	public void generateRemainingAsync(Long productId, Long userId, List<Long> assetIds) {
		if (assetIds == null) {
			return;
		}
		for (Long assetId : assetIds) {
			generateAsync(productId, userId, assetId, false);
		}
	}
}
