package com.expnai.relay.features.product.dto.response;

public record RelayProductSelectionResponse(
		String id,
		String type,
		String pocketId,
		int cur,
		String stepStatus
) {
}

