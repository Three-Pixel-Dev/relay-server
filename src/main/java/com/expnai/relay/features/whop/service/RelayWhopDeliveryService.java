package com.expnai.relay.features.whop.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import com.expnai.relay.common.exception.ResourceNotFoundException;
import com.expnai.relay.domain.entity.RelayBrand;
import com.expnai.relay.domain.entity.RelayProduct;
import com.expnai.relay.domain.entity.RelayProductFile;
import com.expnai.relay.domain.entity.RelayWhopProduct;
import com.expnai.relay.domain.repository.RelayBrandRepository;
import com.expnai.relay.domain.repository.RelayProductFileRepository;
import com.expnai.relay.domain.repository.RelayWhopProductRepository;
import com.expnai.relay.features.product.storage.FileStorageService;

import lombok.RequiredArgsConstructor;

/**
 * Serves a product's export files to buyers. Whop redirects the buyer to
 * {@code /api/public/deliveries/{token}} after checkout; the unguessable token is the access control.
 */
@Service
@RequiredArgsConstructor
public class RelayWhopDeliveryService {

	private final RelayWhopProductRepository whopProductRepository;
	private final RelayProductFileRepository productFileRepository;
	private final RelayBrandRepository brandRepository;
	private final FileStorageService fileStorageService;

	@Transactional(readOnly = true)
	public ResponseEntity<String> page(String token) {
		RelayWhopProduct whop = requireDelivery(token);
		RelayProduct product = whop.getProduct();
		List<RelayProductFile> files = productFileRepository.findByProductIdOrderByIdAsc(product.getId());
		String title = brandRepository.findByProductId(product.getId())
				.map(RelayBrand::getTitle)
				.filter(t -> t != null && !t.isBlank())
				.orElse(product.getName() != null ? product.getName() : "Your purchase");

		StringBuilder items = new StringBuilder();
		for (RelayProductFile file : files) {
			items.append("<li><a href=\"/api/public/deliveries/").append(HtmlUtils.htmlEscape(token))
					.append("/files/").append(file.getId()).append("\">")
					.append(HtmlUtils.htmlEscape(label(file.getFileType()))).append("</a></li>");
		}
		String html = """
				<!doctype html><html lang="en"><head><meta charset="utf-8">
				<meta name="viewport" content="width=device-width,initial-scale=1">
				<meta name="robots" content="noindex">
				<title>%s</title>
				<style>body{font-family:system-ui,sans-serif;max-width:520px;margin:12vh auto;padding:0 20px;color:#16161d}
				h1{font-size:24px}ul{list-style:none;padding:0}li{margin:10px 0}
				a{display:block;padding:14px 16px;border:1px solid #d8d8e0;border-radius:10px;text-decoration:none;color:inherit;font-weight:600}
				a:hover{background:#f4f4f8}p{color:#5b5b68}</style></head>
				<body><h1>%s</h1><p>Thanks for your purchase. Download your files below.</p><ul>%s</ul></body></html>
				"""
				.formatted(HtmlUtils.htmlEscape(title), HtmlUtils.htmlEscape(title), items);
		return ResponseEntity.ok()
				.contentType(MediaType.TEXT_HTML)
				.header(HttpHeaders.CACHE_CONTROL, "no-store")
				.header("X-Robots-Tag", "noindex")
				.body(html);
	}

	@Transactional(readOnly = true)
	public ResponseEntity<Resource> file(String token, Long fileId) {
		RelayWhopProduct whop = requireDelivery(token);
		RelayProductFile file = productFileRepository.findByIdAndProductId(fileId, whop.getProduct().getId())
				.orElseThrow(() -> new ResourceNotFoundException("File not found"));
		Path path = fileStorageService.resolvePath(file.getUrl());
		if (!Files.exists(path) || !Files.isRegularFile(path)) {
			throw new ResourceNotFoundException("File not found");
		}
		return ResponseEntity.ok()
				.contentType(mediaType(file.getFileType()))
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + path.getFileName() + "\"")
				.header(HttpHeaders.CACHE_CONTROL, "no-store")
				.body(new FileSystemResource(path));
	}

	private RelayWhopProduct requireDelivery(String token) {
		if (token == null || token.isBlank()) {
			throw new ResourceNotFoundException("Not found");
		}
		return whopProductRepository.findByDeliveryToken(token)
				.orElseThrow(() -> new ResourceNotFoundException("Not found"));
	}

	private static String label(String fileType) {
		if (fileType == null) {
			return "Download";
		}
		return switch (fileType.toLowerCase(Locale.ROOT)) {
			case "pdf_letter" -> "PDF (Letter)";
			case "pdf_a4" -> "PDF (A4)";
			case "epub" -> "EPUB";
			case "docx" -> "DOCX";
			case "mockups" -> "Mockups";
			case "html" -> "Canva-editable";
			default -> fileType.toUpperCase(Locale.ROOT);
		};
	}

	private static MediaType mediaType(String fileType) {
		if (fileType == null) {
			return MediaType.APPLICATION_OCTET_STREAM;
		}
		return switch (fileType.toLowerCase(Locale.ROOT)) {
			case "pdf_letter", "pdf_a4" -> MediaType.APPLICATION_PDF;
			case "docx" -> MediaType.parseMediaType(
					"application/vnd.openxmlformats-officedocument.wordprocessingml.document");
			case "epub" -> MediaType.parseMediaType("application/epub+zip");
			case "mockups", "html" -> MediaType.parseMediaType("application/zip");
			default -> MediaType.APPLICATION_OCTET_STREAM;
		};
	}
}
