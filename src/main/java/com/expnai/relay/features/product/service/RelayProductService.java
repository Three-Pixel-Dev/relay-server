package com.expnai.relay.features.product.service;

import java.util.List;

import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.expnai.relay.common.web.ApiResponse;
import com.expnai.relay.features.product.dto.request.RelayAddSectionRequest;
import com.expnai.relay.features.product.dto.request.RelayAddSourcesRequest;
import com.expnai.relay.features.product.dto.request.RelayCreateProductRequest;
import com.expnai.relay.features.product.dto.request.RelayRenameProductRequest;
import com.expnai.relay.features.product.dto.request.RelaySuggestChangeRequest;
import com.expnai.relay.features.product.dto.request.RelayUpdateBrandRequest;
import com.expnai.relay.features.product.dto.response.RelayListingDraftResponse;
import com.expnai.relay.features.product.dto.response.RelayProductResponse;
import com.expnai.relay.features.product.dto.response.RelayProductSelectionResponse;

public interface RelayProductService {

	ApiResponse<List<RelayProductResponse>> listProducts();

	ApiResponse<RelayProductResponse> getProduct(Long productId);

	ApiResponse<RelayProductResponse> createProduct(RelayCreateProductRequest request);

	ApiResponse<RelayProductResponse> renameProduct(Long productId, RelayRenameProductRequest request);

	ApiResponse<Void> deleteProduct(Long productId);

	ApiResponse<RelayProductResponse> addSources(Long productId, RelayAddSourcesRequest request);

	ApiResponse<RelayProductResponse> uploadSources(Long productId, List<MultipartFile> files);

	ApiResponse<RelayProductResponse> removeSource(Long productId, Long sourceId);

	ApiResponse<RelayProductResponse> proceed(Long productId);

	ApiResponse<RelayProductResponse> continueQualityGate(Long productId);

	ApiResponse<RelayProductResponse> approve(Long productId);

	ApiResponse<RelayProductSelectionResponse> choosePocket(Long productId, Long pocketId);

	ApiResponse<RelayProductSelectionResponse> chooseType(Long productId, String typeId);

	ApiResponse<RelayProductResponse> addSection(Long productId, RelayAddSectionRequest request);

	ApiResponse<RelayProductResponse> removeSection(Long productId, Long sectionId);

	ApiResponse<RelayProductResponse> regenerateSection(Long productId, Long sectionId);

	ApiResponse<RelayProductResponse> updateBrand(Long productId, RelayUpdateBrandRequest request);

	ApiResponse<RelayListingDraftResponse> generateListingDraft(Long productId);

	ApiResponse<RelayProductResponse> suggest(Long productId, RelaySuggestChangeRequest request);

	ApiResponse<RelayProductResponse> cancel(Long productId);

	ApiResponse<RelayProductResponse> resume(Long productId);

	ResponseEntity<Resource> downloadFile(Long productId, Long fileId);

	ResponseEntity<Resource> getBrandCoverImage(Long productId, int variant);

	ResponseEntity<Resource> getSectionGraphicImage(Long productId, Long sectionId);

	SseEmitter subscribeEvents(Long productId);
}

