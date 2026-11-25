package com.expnai.relay.features.distribute.service;

import com.expnai.relay.common.web.ApiResponse;
import com.expnai.relay.features.product.dto.response.RelayProductResponse;

public interface RelayDistributeService {

	ApiResponse<RelayProductResponse> turnOn(Long productId, String channelId);

	ApiResponse<RelayProductResponse> turnOff(Long productId, String channelId);

	ApiResponse<RelayProductResponse> approveWeek(Long productId);
}
