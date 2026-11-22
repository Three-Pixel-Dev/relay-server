package com.expnai.relay.features.product.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.expnai.relay.common.exception.BusinessRuleException;

@Service
public class FileStorageService {

	private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

	private final Path rootDir;

	public FileStorageService(@Value("${relay.storage.root:./storage}") String storageRoot) {
		this.rootDir = Paths.get(storageRoot).toAbsolutePath().normalize();
		try {
			Files.createDirectories(this.rootDir);
		}
		catch (IOException ex) {
			throw new IllegalStateException("Could not initialize storage directory: " + this.rootDir, ex);
		}
	}

	public String storeMultipartFile(Long productId, MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new BusinessRuleException("Uploaded file is empty.");
		}
		String originalName = file.getOriginalFilename();
		String safeName = sanitizeFilename(originalName != null ? originalName : "document.txt");
		String relativeKey = "products/" + productId + "/" + UUID.randomUUID() + "_" + safeName;
		Path target = resolvePath(relativeKey);
		try {
			Files.createDirectories(target.getParent());
			try (InputStream in = file.getInputStream()) {
				Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
			}
			return relativeKey;
		}
		catch (IOException ex) {
			throw new BusinessRuleException("Failed to store uploaded file: " + safeName);
		}
	}

	public String storeSampleDocument(Long productId, String sampleName, String productInput) {
		String safeName = sanitizeFilename(sampleName != null ? sampleName : "sample-notes.txt");
		String relativeKey = "products/" + productId + "/" + UUID.randomUUID() + "_" + safeName;
		Path target = resolvePath(relativeKey);
		String topic = (productInput != null && !productInput.isBlank())
				? productInput.trim()
				: "practical weekly routines and step-by-step habits";
		String sampleContent = """
				Document: %s
				Subject: %s

				Page 1 — Core Method & First Principles
				When people struggle with %s, the problem is almost never a lack of motivation. It is a sequencing problem.
				Most advice tells beginners to change five habits at once without protecting their environment or setting a clear baseline.
				Our field notes across dozens of client sessions show three repeatable rules that consistently work:
				1. Decide the plan and boundaries before the stressful moment arrives, never in the middle of it.
				2. Protect one controllable variable (distance, timing, or buffer) every single day for 14 days before adding complexity.
				3. Leave or reset early on purpose when early warning signals appear, rather than waiting for a breakdown.

				Page 2 — Common Mistakes and Practical Scripts
				Clients frequently ask what to say or do when things go off script in public or at home.
				The highest-leverage fix is replacing vague intentions with exact one-line scripts and a single-page decision sheet.
				Instead of improvising under pressure, use a pre-agreed checkpoint: observe the early signal, execute the turn or pause, and log what worked.

				Page 3 — Audience, Voice, and Transformation
				Who this is for: Busy adults and first-time practitioners who have tried generic advice and want a calm, realistic workbook they can finish in a weekend.
				Voice and tone: Direct, warm, candid, plain English without academic jargon. Uses concrete everyday examples and step-by-step checklists.
				Expected transformation: Moving from daily overwhelm and second-guessing to a calm, repeatable 14-day system with measurable progress.
				""".formatted(safeName, topic, topic);
		try {
			Files.createDirectories(target.getParent());
			Files.writeString(target, sampleContent, StandardCharsets.UTF_8);
			return relativeKey;
		}
		catch (IOException ex) {
			throw new BusinessRuleException("Failed to create sample document: " + safeName);
		}
	}

	public String storeBytes(Long productId, String filename, byte[] bytes) {
		if (bytes == null || bytes.length == 0) {
			throw new BusinessRuleException("Export file content is empty.");
		}
		String safeName = sanitizeFilename(filename != null ? filename : "export.bin");
		String relativeKey = "products/" + productId + "/exports/" + safeName;
		Path target = resolvePath(relativeKey);
		try {
			Files.createDirectories(target.getParent());
			Files.write(target, bytes);
			return relativeKey;
		}
		catch (IOException ex) {
			throw new BusinessRuleException("Failed to store export file: " + safeName);
		}
	}

	public String storeGraphicBytes(Long productId, String filename, byte[] bytes) {
		if (bytes == null || bytes.length == 0) {
			throw new BusinessRuleException("Graphic image content is empty.");
		}
		String safeName = sanitizeFilename(filename != null ? filename : "graphic.png");
		String relativeKey = "products/" + productId + "/graphics/" + safeName;
		Path target = resolvePath(relativeKey);
		try {
			Files.createDirectories(target.getParent());
			Files.write(target, bytes);
			return relativeKey;
		}
		catch (IOException ex) {
			throw new BusinessRuleException("Failed to store graphic file: " + safeName);
		}
	}

	public Path resolvePath(String storageKey) {
		if (storageKey == null || storageKey.isBlank()) {
			throw new BusinessRuleException("Missing storage key.");
		}
		Path resolved = this.rootDir.resolve(storageKey).normalize();
		if (!resolved.startsWith(this.rootDir)) {
			throw new BusinessRuleException("Invalid storage key path.");
		}
		return resolved;
	}

	public void deleteFile(String storageKey) {
		if (storageKey == null || storageKey.isBlank()) {
			return;
		}
		try {
			Path path = resolvePath(storageKey);
			Files.deleteIfExists(path);
		}
		catch (Exception ex) {
			log.warn("Could not delete stored file {}: {}", storageKey, ex.getMessage());
		}
	}

	public void deleteProductDirectory(Long productId) {
		if (productId == null) {
			return;
		}
		Path dir = this.rootDir.resolve("products").resolve(String.valueOf(productId)).normalize();
		if (!dir.startsWith(this.rootDir) || !Files.exists(dir)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(dir)) {
			walk.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				}
				catch (IOException ignored) {
				}
			});
		}
		catch (IOException ex) {
			log.warn("Could not delete product storage directory {}: {}", dir, ex.getMessage());
		}
	}

	private String sanitizeFilename(String filename) {
		String cleaned = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
		if (cleaned.length() > 180) {
			cleaned = cleaned.substring(cleaned.length() - 180);
		}
		return cleaned.isBlank() ? "file" : cleaned;
	}
}

