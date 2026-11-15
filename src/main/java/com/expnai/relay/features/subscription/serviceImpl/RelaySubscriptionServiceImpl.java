package com.expnai.relay.features.subscription.serviceImpl;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.expnai.relay.common.exception.BusinessRuleException;
import com.expnai.relay.common.exception.InvalidSessionException;
import com.expnai.relay.common.exception.ResourceNotFoundException;
import com.expnai.relay.common.mapper.BaseMapper;
import com.expnai.relay.common.service.BaseServiceImpl;
import com.expnai.relay.common.web.ApiResponse;
import com.expnai.relay.domain.entity.RelaySubscription;
import com.expnai.relay.domain.entity.RelayUser;
import com.expnai.relay.domain.enums.RelaySubscriptionPlan;
import com.expnai.relay.domain.enums.RelaySubscriptionStatus;
import com.expnai.relay.domain.repository.RelaySubscriptionRepository;
import com.expnai.relay.domain.repository.RelayUserRepository;
import com.expnai.relay.features.auth.security.RelayUserPrincipal;
import com.expnai.relay.features.subscription.dto.request.RelaySubscribeRequest;
import com.expnai.relay.features.subscription.dto.response.RelayBillingMessageResponse;
import com.expnai.relay.features.subscription.dto.response.RelayPlanResponse;
import com.expnai.relay.features.subscription.dto.response.RelaySubscriptionResponse;
import com.expnai.relay.features.subscription.mapper.RelaySubscriptionMapper;
import com.expnai.relay.features.subscription.service.RelaySubscriptionService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RelaySubscriptionServiceImpl
		extends BaseServiceImpl<RelaySubscription, RelaySubscriptionResponse, RelaySubscribeRequest, RelaySubscribeRequest>
		implements RelaySubscriptionService {

	private static final List<RelaySubscriptionStatus> ACTIVE_STATUSES = List.of(
			RelaySubscriptionStatus.ACTIVE,
			RelaySubscriptionStatus.PAST_DUE);

	private final RelaySubscriptionRepository subscriptionRepository;
	private final RelayUserRepository userRepository;
	private final RelaySubscriptionMapper subscriptionMapper;

	@Value("${relay.billing.six-months-price-usd:99}")
	private BigDecimal sixMonthsPriceUsd;

	@Value("${relay.billing.yearly-price-usd:149}")
	private BigDecimal yearlyPriceUsd;

	@Override
	protected JpaRepository<RelaySubscription, Long> repository() {
		return subscriptionRepository;
	}

	@Override
	protected BaseMapper<RelaySubscription, RelaySubscriptionResponse> mapper() {
		return subscriptionMapper;
	}

	@Override
	protected RelaySubscription createEntity(RelaySubscribeRequest request) {
		throw new BusinessRuleException("Use subscribe() for creating subscriptions");
	}

	@Override
	protected void updateEntity(RelaySubscription entity, RelaySubscribeRequest request) {
		throw new BusinessRuleException("Use subscribe/cancel/resume for billing changes");
	}

	@Override
	@Transactional(readOnly = true)
	public ApiResponse<List<RelayPlanResponse>> listPlans() {
		return ApiResponse.ok("OK", List.of(
				new RelayPlanResponse(
						RelaySubscriptionPlan.SIX_MONTHS.name(),
						"6 months",
						6,
						sixMonthsPriceUsd,
						false),
				new RelayPlanResponse(
						RelaySubscriptionPlan.YEARLY.name(),
						"Yearly",
						12,
						yearlyPriceUsd,
						false)));
	}

	@Override
	@Transactional(readOnly = true)
	public ApiResponse<RelaySubscriptionResponse> current() {
		RelaySubscription subscription = requireCurrentSubscription(currentUserId());
		return ApiResponse.ok("OK", subscriptionMapper.toDto(subscription));
	}

	@Override
	@Transactional
	public ApiResponse<RelaySubscriptionResponse> subscribe(RelaySubscribeRequest request) {
		RelayUser user = requireCurrentUser();
		RelaySubscriptionPlan plan = request.getPlan();
		if (plan != RelaySubscriptionPlan.SIX_MONTHS && plan != RelaySubscriptionPlan.YEARLY) {
			throw new BusinessRuleException("Only SIX_MONTHS and YEARLY plans are available");
		}

		subscriptionRepository
				.findFirstByUserIdAndStatusInOrderByCreatedAtDesc(user.getId(), ACTIVE_STATUSES)
				.ifPresent(existing -> {
					existing.setStatus(RelaySubscriptionStatus.CANCELED);
					existing.setCanceledAt(Instant.now());
					existing.setCancelAtPeriodEnd(false);
					subscriptionRepository.save(existing);
				});

		Instant now = Instant.now();
		RelaySubscription subscription = new RelaySubscription();
		subscription.setUser(user);
		subscription.setPlan(plan);
		subscription.setStatus(RelaySubscriptionStatus.ACTIVE);
		subscription.setCurrentPeriodStart(now);
		subscription.setCurrentPeriodEnd(now.plus(monthsFor(plan), ChronoUnit.MONTHS));
		subscription.setCancelAtPeriodEnd(false);
		RelaySubscription saved = subscriptionRepository.save(subscription);

		return ApiResponse.created(
				"Subscription activated (payment provider TBD — recorded locally, no trial)",
				subscriptionMapper.toDto(saved));
	}

	@Override
	@Transactional
	public ApiResponse<RelaySubscriptionResponse> cancelAtPeriodEnd() {
		RelaySubscription subscription = requireCurrentSubscription(currentUserId());
		subscription.setCancelAtPeriodEnd(true);
		RelaySubscription saved = subscriptionRepository.save(subscription);
		return ApiResponse.ok("Subscription will cancel at period end", subscriptionMapper.toDto(saved));
	}

	@Override
	@Transactional
	public ApiResponse<RelaySubscriptionResponse> resume() {
		RelaySubscription subscription = requireCurrentSubscription(currentUserId());
		if (!subscription.isCancelAtPeriodEnd()) {
			throw new BusinessRuleException("Subscription is not scheduled to cancel");
		}
		subscription.setCancelAtPeriodEnd(false);
		RelaySubscription saved = subscriptionRepository.save(subscription);
		return ApiResponse.ok("Cancellation withdrawn", subscriptionMapper.toDto(saved));
	}

	@Override
	@Transactional(readOnly = true)
	public ApiResponse<List<RelaySubscriptionResponse>> history() {
		List<RelaySubscriptionResponse> rows = subscriptionRepository
				.findByUserIdOrderByCreatedAtDesc(currentUserId())
				.stream()
				.map(subscriptionMapper::toDto)
				.toList();
		return ApiResponse.ok("OK", rows);
	}

	@Override
	@Transactional(readOnly = true)
	public ApiResponse<RelayBillingMessageResponse> billingPortal() {
		return ApiResponse.ok(
				"Billing portal not available yet",
				new RelayBillingMessageResponse(
						"Payment provider is not connected yet. Plan changes are stored locally until billing is wired."));
	}

	private long monthsFor(RelaySubscriptionPlan plan) {
		return plan == RelaySubscriptionPlan.YEARLY ? 12L : 6L;
	}

	private RelaySubscription requireCurrentSubscription(Long userId) {
		return subscriptionRepository
				.findFirstByUserIdAndStatusInOrderByCreatedAtDesc(userId, ACTIVE_STATUSES)
				.orElseThrow(() -> new ResourceNotFoundException("No active subscription"));
	}

	private RelayUser requireCurrentUser() {
		return userRepository.findById(currentUserId())
				.orElseThrow(() -> new ResourceNotFoundException("User not found"));
	}

	private Long currentUserId() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof RelayUserPrincipal principal)) {
			throw new InvalidSessionException("Not authenticated");
		}
		return principal.getId();
	}
}
