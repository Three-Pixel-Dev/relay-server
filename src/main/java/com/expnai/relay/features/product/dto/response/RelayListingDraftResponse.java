package com.expnai.relay.features.product.dto.response;

import java.math.BigDecimal;

public record RelayListingDraftResponse(
		String title,
		String headline,
		BigDecimal price
) {
}

