package com.expnai.relay.features.auth.dto.response;

public record RelayLoginResponse(
		String accessToken,
		String refreshToken,
		Long userId,
		String email,
		String name,
		String role) {
}
