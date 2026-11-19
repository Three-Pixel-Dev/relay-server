package com.expnai.relay.features.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RelaySetPasswordRequest {

	/** Required when the account already has a password. Ignored when setting the first password. */
	@Size(max = 100)
	private String currentPassword;

	@NotBlank
	@Size(min = 8, max = 100)
	private String newPassword;
}
