package com.expnai.relay.features.auth.dto.response;

import java.time.Instant;

public record RelayMeResponse(
		Long userId,
		String email,
		String name,
		String role,
		boolean onboardSeen,
		Long currentSessionId,
		Instant createdAt,
		RelaySubscriptionSummaryResponse subscription) {
}
