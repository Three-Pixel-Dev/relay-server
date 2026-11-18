package com.expnai.relay.features.product.service;

import java.util.List;

import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.expnai.relay.common.web.ApiResponse;
import com.expnai.relay.features.product.dto.request.RelayAddSourcesRequest;
import com.expnai.relay.features.product.dto.request.RelayCreateProductRequest;
import com.expnai.relay.features.product.dto.request.RelaySuggestChangeRequest;
import com.expnai.relay.features.product.dto.response.RelayProductResponse;

public interface RelayProductService {

	ApiResponse<List<RelayProductResponse>> listProducts();

	ApiResponse<RelayProductResponse> getProduct(Long productId);

	ApiResponse<RelayProductResponse> createProduct(RelayCreateProductRequest request);

	ApiResponse<Void> deleteProduct(Long productId);

	ApiResponse<RelayProductResponse> addSources(Long productId, RelayAddSourcesRequest request);

	ApiResponse<RelayProductResponse> uploadSources(Long productId, List<MultipartFile> files);

	ApiResponse<RelayProductResponse> removeSource(Long productId, Long sourceId);

	ApiResponse<RelayProductResponse> proceed(Long productId);

	ApiResponse<RelayProductResponse> continueQualityGate(Long productId);

	ApiResponse<RelayProductResponse> approve(Long productId);

	ApiResponse<RelayProductResponse> suggest(Long productId, RelaySuggestChangeRequest request);

	ApiResponse<RelayProductResponse> cancel(Long productId);

	ApiResponse<RelayProductResponse> resume(Long productId);

	SseEmitter subscribeEvents(Long productId);
}

