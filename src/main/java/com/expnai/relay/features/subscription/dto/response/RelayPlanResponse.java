package com.expnai.relay.features.subscription.dto.response;

import java.math.BigDecimal;

public record RelayPlanResponse(
		String plan,
		String label,
		int durationMonths,
		BigDecimal priceUsd,
		boolean trialAvailable) {
}
