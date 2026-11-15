package com.expnai.relay.features.auth.mapper;

import org.springframework.stereotype.Component;

import com.expnai.relay.common.mapper.BaseMapper;
import com.expnai.relay.domain.entity.RelaySubscription;
import com.expnai.relay.domain.entity.RelayUser;
import com.expnai.relay.features.auth.dto.response.RelayMeResponse;
import com.expnai.relay.features.auth.dto.response.RelaySubscriptionSummaryResponse;

@Component
public class RelayAuthMapper implements BaseMapper<RelayUser, RelayMeResponse> {

	@Override
	public RelayMeResponse toDto(RelayUser entity) {
		return toMe(entity, null, null);
	}

	public RelayMeResponse toMe(RelayUser user, Long currentSessionId, RelaySubscription subscription) {
		RelaySubscriptionSummaryResponse summary = null;
		if (subscription != null) {
			summary = new RelaySubscriptionSummaryResponse(
					subscription.getId(),
					subscription.getPlan().name(),
					subscription.getStatus().name(),
					subscription.getCurrentPeriodEnd(),
					subscription.isCancelAtPeriodEnd());
		}
		return new RelayMeResponse(
				user.getId(),
				user.getEmail(),
				user.getName(),
				user.getRole().name(),
				user.isOnboardSeen(),
				user.getGoogleId() != null && !user.getGoogleId().isBlank(),
				user.getAvatarUrl(),
				currentSessionId,
				user.getCreatedAt(),
				summary);
	}
}
