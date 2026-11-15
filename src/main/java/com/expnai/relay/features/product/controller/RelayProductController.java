package com.expnai.relay.features.product.controller;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.expnai.relay.common.web.ApiResponse;
import com.expnai.relay.features.product.dto.request.RelayAddSourcesRequest;
import com.expnai.relay.features.product.dto.request.RelayCreateProductRequest;
import com.expnai.relay.features.product.dto.request.RelaySuggestChangeRequest;
import com.expnai.relay.features.product.dto.response.RelayProductResponse;
import com.expnai.relay.features.product.service.RelayProductService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
@Tag(name = "Products")
public class RelayProductController {

	private final RelayProductService productService;

	@GetMapping
	@Operation(summary = "List current user's products")
	public ApiResponse<List<RelayProductResponse>> listProducts() {
		return productService.listProducts();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Create a new product")
	public ApiResponse<RelayProductResponse> createProduct(
			@RequestBody(required = false) RelayCreateProductRequest request) {
		return productService.createProduct(request != null ? request : new RelayCreateProductRequest());
	}

	@GetMapping("/{id}")
	@Operation(summary = "Get product detail by ID")
	public ApiResponse<RelayProductResponse> getProduct(@PathVariable Long id) {
		return productService.getProduct(id);
	}

	@DeleteMapping("/{id}")
	@Operation(summary = "Delete a product and its indexed passages")
	public ApiResponse<Void> deleteProduct(@PathVariable Long id) {
		return productService.deleteProduct(id);
	}

	@PostMapping("/{id}/sources")
	@Operation(summary = "Add link or sample sources to a product")
	public ApiResponse<RelayProductResponse> addSources(
			@PathVariable Long id,
			@RequestBody RelayAddSourcesRequest request) {
		return productService.addSources(id, request);
	}

	@PostMapping(value = "/{id}/sources/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Operation(summary = "Upload source files (multipart) to local storage")
	public ApiResponse<RelayProductResponse> uploadSources(
			@PathVariable Long id,
			@RequestParam(value = "files", required = false) List<MultipartFile> files,
			@RequestParam(value = "file", required = false) MultipartFile singleFile) {
		List<MultipartFile> allFiles = new ArrayList<>();
		if (files != null) {
			allFiles.addAll(files);
		}
		if (singleFile != null) {
			allFiles.add(singleFile);
		}
		return productService.uploadSources(id, allFiles);
	}

	@DeleteMapping("/{id}/sources/{sourceId}")
	@Operation(summary = "Delete a source, remove its pgvector passages, and reset step 0 if active")
	public ApiResponse<RelayProductResponse> removeSource(
			@PathVariable Long id,
			@PathVariable Long sourceId) {
		return productService.removeSource(id, sourceId);
	}

	@PostMapping("/{id}/proceed")
	@Operation(summary = "Proceed on current build step (runs async source ingestion + RAG summary on step 0)")
	public ApiResponse<RelayProductResponse> proceed(@PathVariable Long id) {
		return productService.proceed(id);
	}

	@PostMapping("/{id}/approve")
	@Operation(summary = "Approve current step result (advances step 0 to step 1)")
	public ApiResponse<RelayProductResponse> approve(@PathVariable Long id) {
		return productService.approve(id);
	}

	@PostMapping("/{id}/suggest")
	@Operation(summary = "Suggest a change for current build step (clears profile and stores note on step 0)")
	public ApiResponse<RelayProductResponse> suggest(
			@PathVariable Long id,
			@RequestBody RelaySuggestChangeRequest request) {
		return productService.suggest(id, request);
	}

	@PostMapping("/{id}/cancel")
	@Operation(summary = "Pause/cancel current product")
	public ApiResponse<RelayProductResponse> cancel(@PathVariable Long id) {
		return productService.cancel(id);
	}

	@PostMapping("/{id}/resume")
	@Operation(summary = "Resume a paused product")
	public ApiResponse<RelayProductResponse> resume(@PathVariable Long id) {
		return productService.resume(id);
	}
}

