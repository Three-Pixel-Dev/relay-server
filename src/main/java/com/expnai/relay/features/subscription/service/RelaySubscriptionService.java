package com.expnai.relay.features.subscription.service;

import java.util.List;
import java.util.Map;

import com.expnai.relay.common.service.BaseService;
import com.expnai.relay.common.web.ApiResponse;
import com.expnai.relay.features.subscription.dto.request.RelayConfirmCheckoutRequest;
import com.expnai.relay.features.subscription.dto.request.RelaySubscribeRequest;
import com.expnai.relay.features.subscription.dto.response.RelayBillingMessageResponse;
import com.expnai.relay.features.subscription.dto.response.RelayCheckoutSessionResponse;
import com.expnai.relay.features.subscription.dto.response.RelayPlanResponse;
import com.expnai.relay.features.subscription.dto.response.RelaySubscriptionResponse;

public interface RelaySubscriptionService
		extends BaseService<RelaySubscriptionResponse, RelaySubscribeRequest, RelaySubscribeRequest> {

	ApiResponse<List<RelayPlanResponse>> listPlans();

	ApiResponse<RelaySubscriptionResponse> current();

	/** Dev fallback when Stripe is not configured — activates immediately. */
	ApiResponse<RelaySubscriptionResponse> subscribe(RelaySubscribeRequest request);

	/** Create Stripe Embedded Checkout (card only) for the selected plan. */
	ApiResponse<RelayCheckoutSessionResponse> createCheckout(RelaySubscribeRequest request);

	/** Confirm a paid Checkout Session (client onComplete + webhook both call this path). */
	ApiResponse<RelaySubscriptionResponse> confirmCheckout(RelayConfirmCheckoutRequest request);

	/** Stripe webhook handler (checkout.session.completed). */
	ApiResponse<Map<String, Object>> handleStripeWebhook(String rawBody, String stripeSignature);

	ApiResponse<RelaySubscriptionResponse> cancelAtPeriodEnd();

	ApiResponse<RelaySubscriptionResponse> resume();

	ApiResponse<List<RelaySubscriptionResponse>> history();

	ApiResponse<RelayBillingMessageResponse> billingPortal();
}
