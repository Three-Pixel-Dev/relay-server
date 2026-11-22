package com.expnai.relay.features.partners.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RelayOwnListRequest {

	@NotBlank
	@Size(max = 100_000)
	private String text;
}
