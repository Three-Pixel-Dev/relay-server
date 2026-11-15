package com.expnai.relay.features.auth.dto.response;

import java.time.Instant;

public record RelaySubscriptionSummaryResponse(
		Long id,
		String plan,
		String status,
		Instant currentPeriodEnd,
		boolean cancelAtPeriodEnd) {
}
