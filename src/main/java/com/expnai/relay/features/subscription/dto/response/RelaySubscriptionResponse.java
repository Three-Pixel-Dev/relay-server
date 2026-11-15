package com.expnai.relay.features.subscription.dto.response;

import java.time.Instant;

import com.expnai.relay.common.dto.BaseDto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RelaySubscriptionResponse extends BaseDto {

	private Long userId;
	private String plan;
	private String status;
	private Instant currentPeriodStart;
	private Instant currentPeriodEnd;
	private boolean cancelAtPeriodEnd;
	private Instant canceledAt;
	private String stripeCustomerId;
	private String stripeSubscriptionId;
}
