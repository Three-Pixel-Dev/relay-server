package com.expnai.relay.features.partners.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RelayUpdatePartnerStatusRequest {

	/** interested | declined */
	@NotBlank
	private String status;
}
