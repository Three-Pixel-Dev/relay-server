package com.expnai.relay.domain.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.expnai.relay.common.persistence.BaseRepository;
import com.expnai.relay.domain.entity.RelaySubscription;
import com.expnai.relay.domain.enums.RelaySubscriptionStatus;

@Repository
public interface RelaySubscriptionRepository extends BaseRepository<RelaySubscription> {

	Optional<RelaySubscription> findFirstByUserIdAndStatusInOrderByCreatedAtDesc(
			Long userId,
			List<RelaySubscriptionStatus> statuses);

	List<RelaySubscription> findByUserIdOrderByCreatedAtDesc(Long userId);

	Optional<RelaySubscription> findFirstByStripeSubscriptionId(String stripeSubscriptionId);
}
