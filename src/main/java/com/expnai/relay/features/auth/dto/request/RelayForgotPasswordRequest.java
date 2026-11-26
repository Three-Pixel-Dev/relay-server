package com.expnai.relay.features.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RelayForgotPasswordRequest {

	@NotBlank
	@Email
	private String email;
}
