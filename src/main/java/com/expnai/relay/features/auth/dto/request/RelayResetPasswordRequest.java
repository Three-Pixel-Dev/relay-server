package com.expnai.relay.features.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RelayResetPasswordRequest {

	@NotBlank
	@Size(min = 32, max = 128)
	private String token;

	@NotBlank
	@Size(min = 8, max = 100, message = "Password must be at least 8 characters")
	private String newPassword;
}
