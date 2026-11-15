package com.expnai.relay.features.subscription.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.expnai.relay.common.web.ApiResponse;
import com.expnai.relay.features.subscription.dto.request.RelaySubscribeRequest;
import com.expnai.relay.features.subscription.dto.response.RelayBillingMessageResponse;
import com.expnai.relay.features.subscription.dto.response.RelayPlanResponse;
import com.expnai.relay.features.subscription.dto.response.RelaySubscriptionResponse;
import com.expnai.relay.features.subscription.service.RelaySubscriptionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/subscription")
@RequiredArgsConstructor
@Tag(name = "Subscription")
public class RelaySubscriptionController {

	private final RelaySubscriptionService subscriptionService;

	@GetMapping("/plans")
	@Operation(summary = "List available plans (6 months and yearly, no trial)")
	public ApiResponse<List<RelayPlanResponse>> listPlans() {
		return subscriptionService.listPlans();
	}

	@GetMapping("/me")
	@Operation(summary = "Current subscription")
	public ApiResponse<RelaySubscriptionResponse> current() {
		return subscriptionService.current();
	}

	@PostMapping("/subscribe")
	@Operation(summary = "Start or replace subscription (no trial; payment provider TBD)")
	public ApiResponse<RelaySubscriptionResponse> subscribe(@Valid @RequestBody RelaySubscribeRequest request) {
		return subscriptionService.subscribe(request);
	}

	@PostMapping("/cancel")
	@Operation(summary = "Cancel at period end")
	public ApiResponse<RelaySubscriptionResponse> cancel() {
		return subscriptionService.cancelAtPeriodEnd();
	}

	@PostMapping("/resume")
	@Operation(summary = "Resume a subscription scheduled to cancel")
	public ApiResponse<RelaySubscriptionResponse> resume() {
		return subscriptionService.resume();
	}

	@GetMapping("/history")
	@Operation(summary = "Subscription history for billing management")
	public ApiResponse<List<RelaySubscriptionResponse>> history() {
		return subscriptionService.history();
	}

	@GetMapping("/billing-portal")
	@Operation(summary = "Billing portal placeholder until payment provider is confirmed")
	public ApiResponse<RelayBillingMessageResponse> billingPortal() {
		return subscriptionService.billingPortal();
	}
}
