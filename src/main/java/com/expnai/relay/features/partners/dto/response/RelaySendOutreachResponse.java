package com.expnai.relay.features.partners.dto.response;

import java.util.List;

public record RelaySendOutreachResponse(
		int sent,
		int skippedSuppressed,
		int remainingQueued,
		List<RelayPartnerResponse> partners
) {
}
