package com.expnai.relay.features.subscription.service;

import java.util.List;

import com.expnai.relay.common.service.BaseService;
import com.expnai.relay.common.web.ApiResponse;
import com.expnai.relay.features.subscription.dto.request.RelaySubscribeRequest;
import com.expnai.relay.features.subscription.dto.response.RelayBillingMessageResponse;
import com.expnai.relay.features.subscription.dto.response.RelayPlanResponse;
import com.expnai.relay.features.subscription.dto.response.RelaySubscriptionResponse;

public interface RelaySubscriptionService
		extends BaseService<RelaySubscriptionResponse, RelaySubscribeRequest, RelaySubscribeRequest> {

	ApiResponse<List<RelayPlanResponse>> listPlans();

	ApiResponse<RelaySubscriptionResponse> current();

	ApiResponse<RelaySubscriptionResponse> subscribe(RelaySubscribeRequest request);

	ApiResponse<RelaySubscriptionResponse> cancelAtPeriodEnd();

	ApiResponse<RelaySubscriptionResponse> resume();

	ApiResponse<List<RelaySubscriptionResponse>> history();

	ApiResponse<RelayBillingMessageResponse> billingPortal();
}
