package com.expnai.relay.features.product.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.expnai.relay.common.persistence.AuditUserContext;
import com.expnai.relay.domain.entity.RelayBrand;
import com.expnai.relay.domain.entity.RelayCitation;
import com.expnai.relay.domain.entity.RelayPocket;
import com.expnai.relay.domain.entity.RelayProduct;
import com.expnai.relay.domain.entity.RelaySection;
import com.expnai.relay.domain.entity.RelaySource;
import com.expnai.relay.domain.entity.RelaySourceProfile;
import com.expnai.relay.domain.entity.RelayThreadMessage;
import com.expnai.relay.domain.enums.RelayThreadWho;
import com.expnai.relay.domain.repository.RelayBrandRepository;
import com.expnai.relay.domain.repository.RelayCitationRepository;
import com.expnai.relay.domain.repository.RelayPocketRepository;
import com.expnai.relay.domain.repository.RelayProductRepository;
import com.expnai.relay.domain.repository.RelaySectionRepository;
import com.expnai.relay.domain.repository.RelaySourceProfileRepository;
import com.expnai.relay.domain.repository.RelaySourceRepository;
import com.expnai.relay.domain.repository.RelayThreadMessageRepository;
import com.expnai.relay.features.ai.BrandingLlmService;
import com.expnai.relay.features.ai.BrandingLlmService.GeneratedBrand;
import com.expnai.relay.features.ai.GeminiImageClient;
import com.expnai.relay.features.product.dto.response.RelayProductResponse;
import com.expnai.relay.features.product.mapper.RelayProductMapper;
import com.expnai.relay.features.product.sse.ProductSseHub;
import com.expnai.relay.features.product.storage.FileStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BrandingGenerationWorker {

	private static final Logger log = LoggerFactory.getLogger(BrandingGenerationWorker.class);

	private final RelayProductRepository productRepository;
	private final RelaySourceRepository sourceRepository;
	private final RelaySourceProfileRepository sourceProfileRepository;
	private final RelayPocketRepository pocketRepository;
	private final RelaySectionRepository sectionRepository;
	private final RelayCitationRepository citationRepository;
	private final RelayBrandRepository brandRepository;
	private final RelayThreadMessageRepository threadMessageRepository;
	private final BrandingLlmService brandingLlmService;
	private final GeminiImageClient geminiImageClient;
	private final FileStorageService fileStorageService;
	private final RelayProductMapper productMapper;
	private final ProductSseHub productSseHub;
	private final TransactionTemplate transactionTemplate;
	private final ObjectMapper objectMapper;

	@Async("relayIngestionTaskExecutor")
	public void generateBrandingAsync(Long productId, Long userId, String chatApiKey, String imageApiKey) {
		AuditUserContext.setCurrentUserId(userId);
		try {
			runPipeline(productId, chatApiKey, imageApiKey);
		}
		catch (Exception ex) {
			log.error("Branding generation failed for product {}: {}", productId, ex.getMessage(), ex);
			failStepGracefully(productId, ex.getMessage() != null
					? ex.getMessage()
					: "Could not generate branding. Please try again.");
		}
		finally {
			AuditUserContext.clear();
		}
	}

	private void runPipeline(Long productId, String chatApiKey, String imageApiKey) {
		BrandingContext ctx = transactionTemplate.execute(status -> {
			RelayProduct product = productRepository.findById(productId).orElse(null);
			if (product == null || product.isCancelled()) {
				return null;
			}
			RelayPocket chosenPocket = product.getChosenPocket() != null
					? pocketRepository.findById(product.getChosenPocket().getId()).orElse(null)
					: null;
			RelaySourceProfile profile = sourceProfileRepository.findByProductId(productId).orElse(null);
			List<RelaySection> sections = sectionRepository.findByProductIdOrderBySortOrderAscIdAsc(productId);
			return new BrandingContext(product, chosenPocket, profile, sections);
		});

		if (ctx == null) {
			return;
		}

		List<String> progressLines = new ArrayList<>();
		addProgress(productId, progressLines, "Naming the cover title");
		addProgress(productId, progressLines, "Picking palette and typography");

		GeneratedBrand generated = brandingLlmService.generateBrand(
				chatApiKey,
				ctx.product(),
				ctx.pocket(),
				ctx.profile(),
				ctx.sections());

		if (isProductCancelled(productId)) {
			return;
		}

		addProgress(productId, progressLines, "Drawing three covers");

		List<String> coverImageKeys = new ArrayList<>();
		String imageFallbackWarning = null;
		long stamp = System.currentTimeMillis();
		if (imageApiKey != null && !imageApiKey.isBlank()) {
			for (int variant = 0; variant < 3; variant++) {
				if (isProductCancelled(productId)) {
					return;
				}
				try {
					String prompt = buildCoverPrompt(ctx, generated, variant);
					byte[] pngBytes = geminiImageClient.generatePng(
							imageApiKey, prompt, "3:4", productId, 5, "image_cover");
					String key = fileStorageService.storeGraphicBytes(
							productId, "cover-" + variant + "-" + stamp + ".png", pngBytes);
					coverImageKeys.add(key);
				}
				catch (Exception ex) {
					log.warn("Cover variant {} image generation failed for product {}: {}",
							variant, productId, ex.getMessage());
					coverImageKeys.add(null);
					if (imageFallbackWarning == null) {
						imageFallbackWarning = "Cover image generation fell back to template art (" + ex.getMessage() + ").";
					}
				}
			}
		}
		else {
			imageFallbackWarning = "Image model key not connected — covers stayed template-only.";
		}

		final boolean anyCoverImage = coverImageKeys.stream().anyMatch(k -> k != null && !k.isBlank());
		final String finalWarning = imageFallbackWarning;

		transactionTemplate.executeWithoutResult(status -> {
			RelayProduct product = productRepository.findById(productId).orElse(null);
			if (product == null) {
				return;
			}
			brandRepository.deleteHardByProductId(productId);

			RelayBrand brand = new RelayBrand();
			brand.setProduct(product);
			brand.setTitle(generated.title());
			brand.setPaletteIndex(generated.palette());
			brand.setTypePair(generated.typePair());
			brand.setCoverVariant(generated.cover());
			if (anyCoverImage) {
				try {
					brand.setCoverImageKeysJson(objectMapper.writeValueAsString(coverImageKeys));
				}
				catch (Exception ignored) {
				}
			}
			brandRepository.save(brand);

			product.setStepProgress(null);
			product.setStepStatus("review");
			product.setQualityWarning(finalWarning);
			productRepository.save(product);

			String rationalePart = (generated.rationale() != null && !generated.rationale().isBlank())
					? " " + generated.rationale()
					: "";
			String warningPart = (finalWarning != null && !anyCoverImage)
					? " (" + finalWarning + ")"
					: "";
			saveThreadMessage(product, RelayThreadWho.RELAY,
					"Cover title, palette, and three cover designs are ready." + rationalePart + warningPart
							+ " Pick your cover or tweak the title/palette, then click **Looks good, continue**.");
		});

		publishTerminalProduct(productId);
	}

	private String buildCoverPrompt(BrandingContext ctx, GeneratedBrand generated, int variant) {
		String paletteDesc = switch (generated.palette()) {
			case 1 -> "Calm palette: soft off-white (#f7f6f2), deep navy (#1f2a44), sage teal (#3b7a6e), pale mist (#cfe0d8)";
			case 2 -> "Bold palette: bright ivory (#fffdf5), deep ink (#14121c), golden sun yellow (#ffcf5a), soft cream (#ffe9b0)";
			default -> "Editorial palette: warm paper cream (#f4f1ea), charcoal ink (#25262b), terracotta accent (#8a3b2e), sand (#dccdbf)";
		};
		String layoutMood = switch (variant) {
			case 1 -> "Dark dramatic editorial cover: rich deep ink background, bold luminous centerpiece illustration or abstract graphic in the lower half, crisp light typography at the top.";
			case 2 -> "Warm centered modern book cover: clean light background, centered title typography with a refined minimalist botanical/geometric motif inside a framed card.";
			default -> "Classic high-craft editorial cover: clean light paper background, strong asymmetric typography at the top, rich accent color band and tasteful minimalist spot illustration in the lower half.";
		};
		String niche = ctx.pocket() != null && ctx.pocket().getNiche() != null
				? ctx.pocket().getNiche()
				: (ctx.profile() != null && ctx.profile().getNiche() != null ? ctx.profile().getNiche() : "practical digital guide");
		String typeLabel = ctx.product() != null && ctx.product().getProductType() != null
				? ctx.product().getProductType().getCode().toLowerCase()
				: "guide";
		String suggestion = (ctx.product() != null && ctx.product().getStepNote() != null && !ctx.product().getStepNote().isBlank())
				? "\nUser direction: " + ctx.product().getStepNote().trim()
				: "";

		return """
				Design a polished, publish-ready 3:4 vertical digital %s cover.
				Exact cover title text to render clearly and legibly on the cover: "%s"
				Subject / niche: %s
				Color palette: %s
				Visual style & layout: %s%s
				Rules:
				- Render ONLY the exact title text cleanly with zero typos, no extra lorem ipsum, no watermarks, no 3D book mockups or hands holding a book (flat full-bleed front cover art only).
				- High-contrast, modern, commercially sellable editorial design.
				""".formatted(typeLabel, generated.title(), niche, paletteDesc, layoutMood, suggestion);
	}

	private record BrandingContext(
			RelayProduct product,
			RelayPocket pocket,
			RelaySourceProfile profile,
			List<RelaySection> sections
	) {
	}

	private boolean isProductCancelled(Long productId) {
		return productRepository.findById(productId)
				.map(RelayProduct::isCancelled)
				.orElse(true);
	}

	private void addProgress(Long productId, List<String> progressLines, String newLine) {
		progressLines.add(newLine);
		transactionTemplate.executeWithoutResult(status -> {
			productRepository.findById(productId).ifPresent(product -> {
				try {
					product.setStepProgress(objectMapper.writeValueAsString(progressLines));
					productRepository.save(product);
				}
				catch (Exception ignored) {
				}
			});
		});
		productSseHub.publish(
				productId,
				"progress",
				Map.of("progress", List.copyOf(progressLines), "stepStatus", "running"),
				false);
	}

	private void saveThreadMessage(RelayProduct product, RelayThreadWho who, String text) {
		RelayThreadMessage msg = new RelayThreadMessage();
		msg.setProduct(product);
		msg.setWho(who);
		msg.setText(text);
		threadMessageRepository.save(msg);
	}

	private void failStepGracefully(Long productId, String message) {
		transactionTemplate.executeWithoutResult(status -> {
			productRepository.findById(productId).ifPresent(product -> {
				product.setStepProgress(null);
				product.setStepStatus("todo");
				productRepository.save(product);
				saveThreadMessage(product, RelayThreadWho.RELAY, message);
			});
		});
		RelayProductResponse dto = buildProductResponse(productId);
		if (dto != null) {
			productSseHub.publish(productId, "product", dto, true);
		}
		else {
			productSseHub.publish(productId, "error", Map.of("message", message), true);
		}
	}

	private void publishTerminalProduct(Long productId) {
		RelayProductResponse dto = buildProductResponse(productId);
		if (dto != null) {
			productSseHub.publish(productId, "product", dto, true);
		}
	}

	private RelayProductResponse buildProductResponse(Long productId) {
		return transactionTemplate.execute(status -> {
			RelayProduct product = productRepository.findById(productId).orElse(null);
			if (product == null) {
				return null;
			}
			List<RelaySource> sources = sourceRepository.findByProductIdOrderByCreatedAtAsc(productId);
			RelaySourceProfile profile = sourceProfileRepository.findByProductId(productId).orElse(null);
			List<RelayPocket> pockets = pocketRepository.findByProductIdOrderByIdAsc(productId);
			List<RelaySection> sections = sectionRepository.findByProductIdOrderBySortOrderAscIdAsc(productId);
			List<RelayCitation> citations = sections.isEmpty()
					? List.of()
					: citationRepository.findBySectionIdInOrderBySectionIdAscIdAsc(
							sections.stream().map(RelaySection::getId).toList());
			RelayBrand brand = brandRepository.findByProductId(productId).orElse(null);
			List<RelayThreadMessage> messages = threadMessageRepository
					.findByProductIdOrderByCreatedAtAscIdAsc(productId);
			return productMapper.toDto(product, sources, profile, pockets, sections, citations, brand, messages);
		});
	}
}

