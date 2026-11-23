package com.expnai.relay.features.product.export;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.expnai.relay.common.logging.RelayTrace;
import com.expnai.relay.common.persistence.AuditUserContext;
import com.expnai.relay.domain.entity.RelayBrand;
import com.expnai.relay.domain.entity.RelayCitation;
import com.expnai.relay.domain.entity.RelayPocket;
import com.expnai.relay.domain.entity.RelayProduct;
import com.expnai.relay.domain.entity.RelayProductFile;
import com.expnai.relay.domain.entity.RelaySection;
import com.expnai.relay.domain.entity.RelaySource;
import com.expnai.relay.domain.entity.RelaySourceProfile;
import com.expnai.relay.domain.entity.RelayThreadMessage;
import com.expnai.relay.domain.enums.RelayProductStage;
import com.expnai.relay.domain.enums.RelayThreadWho;
import com.expnai.relay.domain.repository.RelayBrandRepository;
import com.expnai.relay.domain.repository.RelayCitationRepository;
import com.expnai.relay.domain.repository.RelayPocketRepository;
import com.expnai.relay.domain.repository.RelayProductFileRepository;
import com.expnai.relay.domain.repository.RelayProductRepository;
import com.expnai.relay.domain.repository.RelaySectionRepository;
import com.expnai.relay.domain.repository.RelaySourceProfileRepository;
import com.expnai.relay.domain.repository.RelaySourceRepository;
import com.expnai.relay.domain.repository.RelayThreadMessageRepository;
import com.expnai.relay.features.ai.BrandingLlmService;
import com.expnai.relay.features.ai.GeminiImageClient;
import com.expnai.relay.features.product.dto.response.RelayProductResponse;
import com.expnai.relay.features.product.mapper.RelayProductMapper;
import com.expnai.relay.features.product.sse.ProductSseHub;
import com.expnai.relay.features.product.storage.FileStorageService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ReadyExportWorker {

	private static final Logger log = LoggerFactory.getLogger(ReadyExportWorker.class);
	private static final int MAX_SECTION_GRAPHICS = 8;

	private final RelayProductRepository productRepository;
	private final RelaySourceRepository sourceRepository;
	private final RelaySourceProfileRepository sourceProfileRepository;
	private final RelayPocketRepository pocketRepository;
	private final RelaySectionRepository sectionRepository;
	private final RelayCitationRepository citationRepository;
	private final RelayBrandRepository brandRepository;
	private final RelayProductFileRepository productFileRepository;
	private final RelayThreadMessageRepository threadMessageRepository;
	private final GeminiImageClient geminiImageClient;
	private final ProductDocumentRenderer documentRenderer;
	private final FileStorageService fileStorageService;
	private final RelayProductMapper productMapper;
	private final ProductSseHub productSseHub;
	private final TransactionTemplate transactionTemplate;
	private final ObjectMapper objectMapper;

	@Async("relayIngestionTaskExecutor")
	public void exportAsync(Long productId, Long userId, String imageApiKey) {
		AuditUserContext.setCurrentUserId(userId);
		RelayTrace.step("start product={} step=6(ReadyExport)", productId);
		try {
			runPipeline(productId, imageApiKey);
			RelayTrace.step("done product={} step=6(ReadyExport)", productId);
		}
		catch (Exception ex) {
			log.error("Ready export failed for product {}: {}", productId, ex.getMessage(), ex);
			failStepGracefully(productId, ex.getMessage() != null
					? ex.getMessage()
					: "Could not package export files. Please try again.");
		}
		finally {
			AuditUserContext.clear();
		}
	}

	private void runPipeline(Long productId, String imageApiKey) throws Exception {
		ExportContext ctx = transactionTemplate.execute(status -> {
			RelayProduct product = productRepository.findById(productId).orElse(null);
			if (product == null || product.isCancelled()) {
				return null;
			}
			RelayBrand brand = brandRepository.findByProductId(productId).orElse(null);
			List<RelaySection> sections = sectionRepository.findByProductIdOrderBySortOrderAscIdAsc(productId);
			List<RelayProductFile> priorFiles = productFileRepository.findByProductIdOrderByIdAsc(productId);
			int nextVersion = priorFiles.stream().mapToInt(RelayProductFile::getVersion).max().orElse(0) + 1;
			return new ExportContext(product, brand, sections, priorFiles, nextVersion);
		});

		if (ctx == null) {
			return;
		}

		List<String> progressLines = new ArrayList<>();
		addProgress(productId, progressLines, "Laying out pages");

		// Load cover images generated in Branding step
		List<byte[]> allCoverImages = loadCoverImages(ctx.brand());
		int selectedVariant = (ctx.brand() != null && ctx.brand().getCoverVariant() != null)
				? Math.max(0, Math.min(2, ctx.brand().getCoverVariant()))
				: 0;
		byte[] selectedCoverImage = selectedVariant < allCoverImages.size()
				? allCoverImages.get(selectedVariant)
				: null;

		// Generate (or reuse) section spot graphics capped at MAX_SECTION_GRAPHICS
		Map<Long, byte[]> sectionGraphics = generateOrLoadSectionGraphics(
				productId, ctx, imageApiKey, progressLines);

		if (isProductCancelled(productId)) {
			return;
		}

		String slug = slugify(ctx.brand() != null && ctx.brand().getTitle() != null
				? ctx.brand().getTitle()
				: ctx.product().getName());
		int version = ctx.nextVersion();

		addProgress(productId, progressLines, "Rendering PDF (A4 + US Letter)");
		byte[] pdfLetter = documentRenderer.renderPdf(
				ctx.brand(), ctx.sections(), ctx.product().getProductType(), PDRectangle.LETTER,
				selectedCoverImage, sectionGraphics);
		byte[] pdfA4 = documentRenderer.renderPdf(
				ctx.brand(), ctx.sections(), ctx.product().getProductType(), PDRectangle.A4,
				selectedCoverImage, sectionGraphics);

		if (isProductCancelled(productId)) {
			return;
		}

		addProgress(productId, progressLines, "Rendering EPUB and DOCX");
		byte[] epub = documentRenderer.renderEpub(
				ctx.brand(), ctx.sections(), selectedCoverImage, sectionGraphics);
		byte[] docx = documentRenderer.renderDocx(
				ctx.brand(), ctx.sections(), selectedCoverImage, sectionGraphics);

		if (isProductCancelled(productId)) {
			return;
		}

		addProgress(productId, progressLines, "Making mockups");
		byte[] mockups = documentRenderer.renderMockupsZip(
				ctx.brand(), ctx.sections(), allCoverImages, sectionGraphics);
		byte[] htmlPack = documentRenderer.renderHtmlPackZip(
				ctx.brand(), ctx.sections(), selectedCoverImage, sectionGraphics);

		if (isProductCancelled(productId)) {
			return;
		}

		// Delete any prior stored export files on disk
		for (RelayProductFile oldFile : ctx.priorFiles()) {
			fileStorageService.deleteFile(oldFile.getUrl());
		}

		String keyPdfLetter = fileStorageService.storeBytes(productId, slug + "-letter-v" + version + ".pdf", pdfLetter);
		String keyPdfA4 = fileStorageService.storeBytes(productId, slug + "-a4-v" + version + ".pdf", pdfA4);
		String keyEpub = fileStorageService.storeBytes(productId, slug + "-v" + version + ".epub", epub);
		String keyDocx = fileStorageService.storeBytes(productId, slug + "-v" + version + ".docx", docx);
		String keyMockups = fileStorageService.storeBytes(productId, slug + "-mockups-v" + version + ".zip", mockups);
		String keyHtml = fileStorageService.storeBytes(productId, slug + "-html-v" + version + ".zip", htmlPack);

		transactionTemplate.executeWithoutResult(status -> {
			RelayProduct product = productRepository.findById(productId).orElse(null);
			if (product == null) {
				return;
			}
			productFileRepository.deleteHardByProductId(productId);

			saveFileRow(product, "pdf_letter", version, keyPdfLetter);
			saveFileRow(product, "pdf_a4", version, keyPdfA4);
			saveFileRow(product, "epub", version, keyEpub);
			saveFileRow(product, "docx", version, keyDocx);
			saveFileRow(product, "mockups", version, keyMockups);
			saveFileRow(product, "html", version, keyHtml);

			int totalWords = product.getWordCount() != null && product.getWordCount() > 0
					? product.getWordCount()
					: ctx.sections().stream().mapToInt(s -> s.getWords() != null ? s.getWords() : 0).sum();
			int sectionCount = ctx.sections().size();
			int estPages = Math.max(8, Math.round(totalWords / 250f) + sectionCount + 2);
			String title = (ctx.brand() != null && ctx.brand().getTitle() != null && !ctx.brand().getTitle().isBlank())
					? ctx.brand().getTitle().trim()
					: (product.getName() != null && !product.getName().isBlank() ? product.getName().trim() : "Your product");

			product.setBuildStep(6);
			product.setStepStatus("done");
			product.setStage(RelayProductStage.PUBLISH);
			product.setStepProgress(null);
			productRepository.save(product);

			saveThreadMessage(product, RelayThreadWho.RELAY,
					"**" + title + "** is ready: "
							+ sectionCount + (sectionCount == 1 ? " section, " : " sections, ")
							+ totalWords + " words, ~" + estPages + " pages. Files are yours. Next: list it where it sells.");
		});

		publishTerminalProduct(productId);
	}

	private List<byte[]> loadCoverImages(RelayBrand brand) {
		List<byte[]> result = new ArrayList<>();
		if (brand == null || brand.getCoverImageKeysJson() == null || brand.getCoverImageKeysJson().isBlank()) {
			return result;
		}
		try {
			List<String> keys = objectMapper.readValue(
					brand.getCoverImageKeysJson(), new TypeReference<List<String>>() {
					});
			for (String key : keys) {
				result.add(readStoredBytesQuietly(key));
			}
		}
		catch (Exception ex) {
			log.warn("Could not parse coverImageKeysJson: {}", ex.getMessage());
		}
		return result;
	}

	private Map<Long, byte[]> generateOrLoadSectionGraphics(
			Long productId,
			ExportContext ctx,
			String imageApiKey,
			List<String> progressLines) {
		Map<Long, byte[]> bytesBySection = new LinkedHashMap<>();
		Map<String, String> keyMap = new LinkedHashMap<>();

		if (ctx.brand() != null && ctx.brand().getPageGraphicsJson() != null
				&& !ctx.brand().getPageGraphicsJson().isBlank()) {
			try {
				Map<String, String> existing = objectMapper.readValue(
						ctx.brand().getPageGraphicsJson(), new TypeReference<Map<String, String>>() {
						});
				if (existing != null) {
					keyMap.putAll(existing);
				}
			}
			catch (Exception ignored) {
			}
		}

		int limit = Math.min(ctx.sections().size(), MAX_SECTION_GRAPHICS);
		if (limit > 0 && imageApiKey != null && !imageApiKey.isBlank()) {
			addProgress(productId, progressLines, "Drawing section graphics");
		}

		String paletteHint = buildPaletteHint(ctx.brand());

		boolean updatedKeys = false;
		long stamp = System.currentTimeMillis();
		for (int i = 0; i < limit; i++) {
			if (isProductCancelled(productId)) {
				break;
			}
			RelaySection sec = ctx.sections().get(i);
			if (sec.getId() == null) {
				continue;
			}
			String secIdStr = String.valueOf(sec.getId());
			String existingKey = keyMap.get(secIdStr);
			byte[] existingBytes = readStoredBytesQuietly(existingKey);
			if (existingBytes != null) {
				bytesBySection.put(sec.getId(), existingBytes);
				continue;
			}

			if (imageApiKey == null || imageApiKey.isBlank()) {
				continue;
			}
			try {
				String prompt = """
						Create a clean, minimalist editorial spot illustration for a digital book section header.
						Section title: "%s"
						Reader moment: "%s"
						Palette: %s
						Rules:
						- Pure visual illustration only — NO text, NO words, NO letters, NO numbers, NO watermarks.
						- Clean white or matching soft background, crisp modern flat/editorial vector-like style, balanced 4:3 composition.
						""".formatted(
						sec.getTitle() != null ? sec.getTitle() : ("Section " + (i + 1)),
						sec.getMoment() != null ? sec.getMoment() : "",
						paletteHint);
				byte[] png = geminiImageClient.generatePng(
						imageApiKey, prompt, "4:3", productId, 6, "image_page");
				String key = fileStorageService.storeGraphicBytes(
						productId, "section-" + sec.getId() + "-" + stamp + ".png", png);
				keyMap.put(secIdStr, key);
				bytesBySection.put(sec.getId(), png);
				updatedKeys = true;
			}
			catch (Exception ex) {
				log.warn("Section {} graphic generation failed for product {}: {}",
						sec.getId(), productId, ex.getMessage());
			}
		}

		if (updatedKeys && ctx.brand() != null) {
			try {
				String json = objectMapper.writeValueAsString(keyMap);
				transactionTemplate.executeWithoutResult(status -> brandRepository.findByProductId(productId)
						.ifPresent(b -> {
							b.setPageGraphicsJson(json);
							brandRepository.save(b);
						}));
			}
			catch (Exception ignored) {
			}
		}

		return bytesBySection;
	}

	private String buildPaletteHint(RelayBrand brand) {
		BrandingLlmService.BrandPalette pal = null;
		if (brand != null && brand.getPaletteJson() != null && !brand.getPaletteJson().isBlank()) {
			try {
				BrandingLlmService.BrandPalette parsed = objectMapper.readValue(
						brand.getPaletteJson(), BrandingLlmService.BrandPalette.class);
				if (parsed != null && parsed.cols() != null && parsed.cols().size() >= 5) {
					pal = parsed;
				}
			}
			catch (Exception ignored) {
			}
		}
		if (pal == null) {
			pal = BrandingLlmService.housePalette(brand != null ? brand.getPaletteIndex() : 0);
		}
		List<String> cols = pal.cols();
		return String.format(
				"%s palette (page %s, ink %s, accent %s, tint %s, muted %s)",
				pal.name() != null && !pal.name().isBlank() ? pal.name() : "Custom",
				cols.get(0), cols.get(1), cols.get(2), cols.get(3), cols.get(4));
	}

	private byte[] readStoredBytesQuietly(String storageKey) {
		if (storageKey == null || storageKey.isBlank()) {
			return null;
		}
		try {
			Path path = fileStorageService.resolvePath(storageKey);
			if (Files.exists(path) && Files.isRegularFile(path)) {
				return Files.readAllBytes(path);
			}
		}
		catch (Exception ignored) {
		}
		return null;
	}

	private void saveFileRow(RelayProduct product, String fileType, int version, String storageKey) {
		RelayProductFile row = new RelayProductFile();
		row.setProduct(product);
		row.setFileType(fileType);
		row.setVersion(version);
		row.setUrl(storageKey);
		productFileRepository.save(row);
	}

	private String slugify(String raw) {
		if (raw == null || raw.isBlank()) {
			return "relay-product";
		}
		String slug = raw.toLowerCase(Locale.ROOT)
				.replaceAll("[^a-z0-9]+", "-")
				.replaceAll("^-+|-+$", "");
		if (slug.length() > 60) {
			slug = slug.substring(0, 60).replaceAll("-+$", "");
		}
		return slug.isBlank() ? "relay-product" : slug;
	}

	private record ExportContext(
			RelayProduct product,
			RelayBrand brand,
			List<RelaySection> sections,
			List<RelayProductFile> priorFiles,
			int nextVersion
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
			List<RelayProductFile> files = productFileRepository.findByProductIdOrderByIdAsc(productId);
			List<RelayThreadMessage> messages = threadMessageRepository
					.findByProductIdOrderByCreatedAtAscIdAsc(productId);
			return productMapper.toDto(product, sources, profile, pockets, sections, citations, brand, files, messages);
		});
	}
}

