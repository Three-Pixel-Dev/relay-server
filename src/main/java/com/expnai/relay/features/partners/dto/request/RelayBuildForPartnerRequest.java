package com.expnai.relay.features.partners.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RelayBuildForPartnerRequest {

	/** guided | fast — defaults to guided */
	@Size(max = 20)
	private String mode;
}
