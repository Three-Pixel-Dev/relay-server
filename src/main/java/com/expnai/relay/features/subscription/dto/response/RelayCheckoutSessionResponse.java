package com.expnai.relay.features.subscription.dto.response;

public record RelayCheckoutSessionResponse(
		String sessionId,
		String clientSecret,
		String publishableKey,
		String plan,
		String mode) {
}
