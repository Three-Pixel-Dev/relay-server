package com.expnai.relay.features.partners.dto.response;

import java.util.List;

public record RelayPartnersOverviewResponse(
		List<RelayPartnerResponse> partners,
		RelayOutreachTemplateResponse outreach,
		boolean mailConfigured,
		String mailFrom
) {
}
