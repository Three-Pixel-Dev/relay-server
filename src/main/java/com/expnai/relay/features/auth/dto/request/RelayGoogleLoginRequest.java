package com.expnai.relay.features.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Client sends the Google ID token from Sign-In / AuthSession.
 * Server verifies the token cryptographically — never trusts client profile fields alone.
 */
@Getter
@Setter
public class RelayGoogleLoginRequest {

	@NotBlank(message = "Google ID token is required")
	private String idToken;
}
