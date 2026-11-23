package com.expnai.relay.features.whop.controller;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.expnai.relay.common.web.ApiResponse;
import com.expnai.relay.features.product.dto.response.RelayProductResponse;
import com.expnai.relay.features.whop.dto.request.RelayWhopPublishRequest;
import com.expnai.relay.features.whop.service.RelayWhopService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/whop")
@RequiredArgsConstructor
@Tag(name = "Whop")
public class RelayWhopController {

	private final RelayWhopService whopService;

	@PostMapping("/products/{productId}/publish")
	@Operation(
			summary = "Publish a finished product to the user's Whop account",
			description = "Creates the Whop product and one-time plan, wires file delivery, and returns the product with its checkout link. Safe to retry: an already published product is returned unchanged.")
	public ApiResponse<RelayProductResponse> publish(
			@PathVariable Long productId,
			@RequestBody @Valid RelayWhopPublishRequest request) {
		return whopService.publish(productId, request);
	}
}
