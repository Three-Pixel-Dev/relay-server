package com.expnai.relay.features.partners.dto.request;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RelayScoutRequest {

	@Size(max = 200)
	private String query = "";

	private List<String> platforms = new ArrayList<>(List.of("youtube", "instagram"));
}
