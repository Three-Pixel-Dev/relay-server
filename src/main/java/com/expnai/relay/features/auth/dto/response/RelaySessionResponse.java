package com.expnai.relay.features.auth.dto.response;

import java.time.Instant;

public record RelaySessionResponse(
		Long id,
		String deviceLabel,
		String ipAddress,
		Instant createdAt,
		Instant lastActiveAt,
		boolean current) {
}
