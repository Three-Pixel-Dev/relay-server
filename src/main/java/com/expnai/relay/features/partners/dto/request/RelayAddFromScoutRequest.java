package com.expnai.relay.features.partners.dto.request;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RelayAddFromScoutRequest {

	@NotEmpty
	@Valid
	private List<RelayScoutCreatorRequest> creators = new ArrayList<>();
}
