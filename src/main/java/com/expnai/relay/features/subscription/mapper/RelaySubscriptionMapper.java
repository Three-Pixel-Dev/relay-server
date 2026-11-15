package com.expnai.relay.features.subscription.mapper;

import org.springframework.stereotype.Component;

import com.expnai.relay.common.mapper.BaseMapper;
import com.expnai.relay.domain.entity.RelaySubscription;
import com.expnai.relay.features.subscription.dto.response.RelaySubscriptionResponse;

@Component
public class RelaySubscriptionMapper implements BaseMapper<RelaySubscription, RelaySubscriptionResponse> {

	@Override
	public RelaySubscriptionResponse toDto(RelaySubscription entity) {
		RelaySubscriptionResponse dto = new RelaySubscriptionResponse();
		dto.setId(entity.getId());
		dto.setCreatedAt(entity.getCreatedAt());
		dto.setUpdatedAt(entity.getUpdatedAt());
		dto.setUserId(entity.getUser() != null ? entity.getUser().getId() : null);
		dto.setPlan(entity.getPlan() != null ? entity.getPlan().name() : null);
		dto.setStatus(entity.getStatus() != null ? entity.getStatus().name() : null);
		dto.setCurrentPeriodStart(entity.getCurrentPeriodStart());
		dto.setCurrentPeriodEnd(entity.getCurrentPeriodEnd());
		dto.setCancelAtPeriodEnd(entity.isCancelAtPeriodEnd());
		dto.setCanceledAt(entity.getCanceledAt());
		dto.setStripeCustomerId(entity.getStripeCustomerId());
		dto.setStripeSubscriptionId(entity.getStripeSubscriptionId());
		return dto;
	}
}
