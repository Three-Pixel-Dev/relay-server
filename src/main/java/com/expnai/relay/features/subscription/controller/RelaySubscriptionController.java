package com.expnai.relay.features.subscription.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.expnai.relay.common.web.ApiResponse;
import com.expnai.relay.features.subscription.dto.request.RelayConfirmCheckoutRequest;
import com.expnai.relay.features.subscription.dto.request.RelaySubscribeRequest;
import com.expnai.relay.features.subscription.dto.response.RelayBillingMessageResponse;
import com.expnai.relay.features.subscription.dto.response.RelayCheckoutSessionResponse;
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
	@Operation(summary = "Dev-only free activate when Stripe is not configured")
	public ApiResponse<RelaySubscriptionResponse> subscribe(@Valid @RequestBody RelaySubscribeRequest request) {
		return subscriptionService.subscribe(request);
	}

	@PostMapping("/checkout")
	@Operation(summary = "Create Stripe Embedded Checkout session (card only)")
	public ApiResponse<RelayCheckoutSessionResponse> checkout(@Valid @RequestBody RelaySubscribeRequest request) {
		return subscriptionService.createCheckout(request);
	}

	@PostMapping("/checkout/confirm")
	@Operation(summary = "Confirm paid Stripe Checkout session and activate plan")
	public ApiResponse<RelaySubscriptionResponse> confirmCheckout(
			@Valid @RequestBody RelayConfirmCheckoutRequest request) {
		return subscriptionService.confirmCheckout(request);
	}

	@PostMapping(value = "/webhooks/stripe", consumes = MediaType.APPLICATION_JSON_VALUE)
	@Operation(summary = "Stripe webhook (checkout.session.completed)")
	public ApiResponse<Map<String, Object>> stripeWebhook(
			@RequestBody String rawBody,
			@RequestHeader(value = "Stripe-Signature", required = false) String signature) {
		return subscriptionService.handleStripeWebhook(rawBody, signature);
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
	@Operation(summary = "Billing management notes")
	public ApiResponse<RelayBillingMessageResponse> billingPortal() {
		return subscriptionService.billingPortal();
	}
}
