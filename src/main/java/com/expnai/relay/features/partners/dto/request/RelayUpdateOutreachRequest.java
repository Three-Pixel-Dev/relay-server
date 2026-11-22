package com.expnai.relay.features.partners.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RelayUpdateOutreachRequest {

	@Size(max = 500)
	private String subject;

	@Size(max = 20_000)
	private String body;

	@Size(max = 10_000)
	private String f1;

	@Size(max = 10_000)
	private String f2;

	@Min(30)
	@Max(60)
	private Integer split;

	@Min(1)
	@Max(100)
	private Integer cap;
}
