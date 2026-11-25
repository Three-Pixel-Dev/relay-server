package com.expnai.relay.features.distribute.controller;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.expnai.relay.common.web.ApiResponse;
import com.expnai.relay.features.distribute.service.RelayDistributeService;
import com.expnai.relay.features.product.dto.response.RelayProductResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/products/{productId}/distribute")
@RequiredArgsConstructor
@Tag(name = "Distribute")
public class RelayDistributeController {

	private final RelayDistributeService distributeService;

	@PostMapping("/{channel}/on")
	@Operation(summary = "Turn on a distribute channel (validates Connections needs; Phase A stubs for seo/relay/affiliates)")
	public ApiResponse<RelayProductResponse> turnOn(
			@PathVariable Long productId,
			@PathVariable String channel) {
		return distributeService.turnOn(productId, channel);
	}

	@PostMapping("/{channel}/off")
	@Operation(summary = "Turn off a distribute channel (stops future weekly batches; keeps remote assets)")
	public ApiResponse<RelayProductResponse> turnOff(
			@PathVariable Long productId,
			@PathVariable String channel) {
		return distributeService.turnOff(productId, channel);
	}

	@PostMapping("/week")
	@Operation(summary = "Approve this week's batch for all enabled channels (creates RelayBatch; nothing posts without this)")
	public ApiResponse<RelayProductResponse> approveWeek(@PathVariable Long productId) {
		return distributeService.approveWeek(productId);
	}
}
