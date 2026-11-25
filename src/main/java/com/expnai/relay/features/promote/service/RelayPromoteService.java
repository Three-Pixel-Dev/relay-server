package com.expnai.relay.features.promote.service;

import com.expnai.relay.common.web.ApiResponse;
import com.expnai.relay.features.product.dto.response.RelayProductResponse;

public interface RelayPromoteService {

	ApiResponse<RelayProductResponse> start(Long productId);

	ApiResponse<RelayProductResponse> approve(Long productId);

	ApiResponse<RelayProductResponse> redo(Long productId);

	ApiResponse<RelayProductResponse> schedule(Long productId);
}
