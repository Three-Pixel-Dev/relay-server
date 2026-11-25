package com.expnai.relay.features.promote.controller;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.expnai.relay.common.web.ApiResponse;
import com.expnai.relay.features.product.dto.response.RelayProductResponse;
import com.expnai.relay.features.promote.service.RelayPromoteService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/products/{productId}/promote")
@RequiredArgsConstructor
@Tag(name = "Promote")
public class RelayPromoteController {

	private final RelayPromoteService promoteService;

	@PostMapping("/start")
	@Operation(summary = "Start first promo Short (spend estimate; requires Higgsfield; stub generation to GATE)")
	public ApiResponse<RelayProductResponse> start(@PathVariable Long productId) {
		return promoteService.start(productId);
	}

	@PostMapping("/approve")
	@Operation(summary = "Approve first-video gate; create remaining 9 videos (stub)")
	public ApiResponse<RelayProductResponse> approve(@PathVariable Long productId) {
		return promoteService.approve(productId);
	}

	@PostMapping("/redo")
	@Operation(summary = "Regenerate the first promo video (gate only)")
	public ApiResponse<RelayProductResponse> redo(@PathVariable Long productId) {
		return promoteService.redo(productId);
	}

	@PostMapping("/schedule")
	@Operation(summary = "Schedule 10 Shorts one per day (requires reach account; nothing posts yet)")
	public ApiResponse<RelayProductResponse> schedule(@PathVariable Long productId) {
		return promoteService.schedule(productId);
	}
}
