package com.expnai.relay.features.partners.dto.response;

public record RelayOutreachTemplateResponse(
		String subject,
		String body,
		String f1,
		String f2,
		int split,
		int cap
) {
}
