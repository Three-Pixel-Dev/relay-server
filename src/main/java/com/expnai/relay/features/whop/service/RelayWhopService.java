package com.expnai.relay.features.whop.service;

import com.expnai.relay.common.web.ApiResponse;
import com.expnai.relay.domain.entity.RelayServiceConnection;
import com.expnai.relay.features.product.dto.response.RelayProductResponse;
import com.expnai.relay.features.whop.dto.request.RelayWhopPublishRequest;

public interface RelayWhopService {

	/** Validates the API key against Whop, registers the sales webhook and stores both on the connection. */
	void connect(RelayServiceConnection connection, String apiKey);

	/** Removes the webhook (best effort) and forgets its token and secret. */
	void disconnect(RelayServiceConnection connection);

	/** Creates the Whop product and checkout link for a finished product and records the listing. */
	ApiResponse<RelayProductResponse> publish(Long productId, RelayWhopPublishRequest request);
}
