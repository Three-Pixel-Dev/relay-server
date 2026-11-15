package com.expnai.relay.features.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RelayRefreshTokenRequest {

	@NotBlank
	private String refreshToken;
}
