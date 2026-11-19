package com.expnai.relay.features.subscription.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RelayConfirmCheckoutRequest {

	@NotBlank(message = "sessionId is required")
	private String sessionId;
}
