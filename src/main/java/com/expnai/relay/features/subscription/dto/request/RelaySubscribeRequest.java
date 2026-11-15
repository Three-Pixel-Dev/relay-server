package com.expnai.relay.features.subscription.dto.request;

import com.expnai.relay.domain.enums.RelaySubscriptionPlan;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RelaySubscribeRequest {

	@NotNull
	private RelaySubscriptionPlan plan;
}
