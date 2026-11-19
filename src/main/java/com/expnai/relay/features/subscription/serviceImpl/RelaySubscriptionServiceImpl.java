package com.expnai.relay.features.subscription.serviceImpl;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
import com.expnai.relay.features.subscription.dto.request.RelayConfirmCheckoutRequest;
import com.expnai.relay.features.subscription.dto.request.RelaySubscribeRequest;
import com.expnai.relay.features.subscription.dto.response.RelayBillingMessageResponse;
import com.expnai.relay.features.subscription.dto.response.RelayCheckoutSessionResponse;
import com.expnai.relay.features.subscription.dto.response.RelayPlanResponse;
import com.expnai.relay.features.subscription.dto.response.RelaySubscriptionResponse;
import com.expnai.relay.features.subscription.mapper.RelaySubscriptionMapper;
import com.expnai.relay.features.subscription.service.RelayStripeCardCheckoutService;
import com.expnai.relay.features.subscription.service.RelayStripeCardCheckoutService.PaidCheckoutPayload;
import com.expnai.relay.features.subscription.service.RelayStripeCardCheckoutService.SessionCreateResult;
import com.expnai.relay.features.subscription.service.RelaySubscriptionService;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RelaySubscriptionServiceImpl
		extends BaseServiceImpl<RelaySubscription, RelaySubscriptionResponse, RelaySubscribeRequest, RelaySubscribeRequest>
		implements RelaySubscriptionService {

	private static final Logger log = LoggerFactory.getLogger(RelaySubscriptionServiceImpl.class);

	private static final List<RelaySubscriptionStatus> ACTIVE_STATUSES = List.of(
			RelaySubscriptionStatus.ACTIVE,
			RelaySubscriptionStatus.PAST_DUE);

	private final RelaySubscriptionRepository subscriptionRepository;
	private final RelayUserRepository userRepository;
	private final RelaySubscriptionMapper subscriptionMapper;
	private final RelayStripeCardCheckoutService stripeCardCheckoutService;

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
		throw new BusinessRuleException("Use createCheckout() / subscribe() for creating subscriptions");
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
		if (stripeCardCheckoutService.isConfigured()) {
			throw new BusinessRuleException(
					"Stripe is configured. Use POST /api/subscription/checkout to pay by card.");
		}
		RelayUser user = requireCurrentUser();
		RelaySubscriptionPlan plan = requireSupportedPlan(request.getPlan());
		RelaySubscription saved = activatePlan(user, plan, null, null);
		return ApiResponse.created(
				"Subscription activated locally (Stripe not configured)",
				subscriptionMapper.toDto(saved));
	}

	@Override
	@Transactional
	public ApiResponse<RelayCheckoutSessionResponse> createCheckout(RelaySubscribeRequest request) {
		RelayUser user = requireCurrentUser();
		RelaySubscriptionPlan plan = requireSupportedPlan(request.getPlan());
		SessionCreateResult session = stripeCardCheckoutService.createEmbeddedCheckout(user, plan);
		return ApiResponse.ok(
				"Checkout session created",
				new RelayCheckoutSessionResponse(
						session.sessionId(),
						session.clientSecret(),
						session.publishableKey(),
						session.plan(),
						"embedded"));
	}

	@Override
	@Transactional
	public ApiResponse<RelaySubscriptionResponse> confirmCheckout(RelayConfirmCheckoutRequest request) {
		RelayUser user = requireCurrentUser();
		Session session = stripeCardCheckoutService.retrieveSession(request.getSessionId());
		PaidCheckoutPayload payload = stripeCardCheckoutService.requirePaidPayload(session);
		if (!payload.userId().equals(user.getId())) {
			throw new BusinessRuleException("Checkout session does not belong to the current user.");
		}
		RelaySubscription saved = activateFromPaidCheckout(payload);
		return ApiResponse.ok("Subscription activated", subscriptionMapper.toDto(saved));
	}

	@Override
	@Transactional
	public ApiResponse<Map<String, Object>> handleStripeWebhook(String rawBody, String stripeSignature) {
		Event event = stripeCardCheckoutService.constructEvent(rawBody, stripeSignature);
		String type = event.getType();
		if ("checkout.session.completed".equals(type) || "checkout.session.async_payment_succeeded".equals(type)) {
			Session session = unwrapSession(event);
			if (session != null) {
				try {
					PaidCheckoutPayload payload = stripeCardCheckoutService.requirePaidPayload(session);
					activateFromPaidCheckout(payload);
					return ApiResponse.ok("OK", Map.of("activated", true, "sessionId", session.getId()));
				} catch (BusinessRuleException ex) {
					log.info("Stripe webhook session not activated yet: {}", ex.getMessage());
					return ApiResponse.ok("OK", Map.of("activated", false, "reason", ex.getMessage()));
				}
			}
		}
		return ApiResponse.ok("OK", Map.of("accepted", true, "type", type != null ? type : ""));
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
		if (stripeCardCheckoutService.isConfigured()) {
			return ApiResponse.ok(
					"OK",
					new RelayBillingMessageResponse(
							"Card billing runs through Stripe Checkout. Cancel or resume from Connections, or pick a new plan to pay again."));
		}
		return ApiResponse.ok(
				"Billing portal not available yet",
				new RelayBillingMessageResponse(
						"Stripe is not configured. Set STRIPE_SECRET_KEY / STRIPE_PUBLISHABLE_KEY to enable card checkout."));
	}

	private RelaySubscription activateFromPaidCheckout(PaidCheckoutPayload payload) {
		return subscriptionRepository
				.findFirstByStripeSubscriptionId(payload.checkoutSessionId())
				.orElseGet(() -> {
					RelayUser user = userRepository.findById(payload.userId())
							.orElseThrow(() -> new ResourceNotFoundException("User not found"));
					return activatePlan(user, payload.plan(), payload.checkoutSessionId(), payload.stripeCustomerId());
				});
	}

	private RelaySubscription activatePlan(
			RelayUser user,
			RelaySubscriptionPlan plan,
			String checkoutSessionId,
			String stripeCustomerId) {
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
		subscription.setCurrentPeriodEnd(now.atZone(ZoneOffset.UTC).plusMonths(monthsFor(plan)).toInstant());
		subscription.setCancelAtPeriodEnd(false);
		if (StringUtils.hasText(checkoutSessionId)) {
			subscription.setStripeSubscriptionId(checkoutSessionId);
		}
		if (StringUtils.hasText(stripeCustomerId)) {
			subscription.setStripeCustomerId(stripeCustomerId);
		}
		return subscriptionRepository.save(subscription);
	}

	private Session unwrapSession(Event event) {
		EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();
		StripeObject obj = deserializer.getObject().orElse(null);
		if (obj instanceof Session session) {
			return session;
		}
		String raw = deserializer.getRawJson();
		if (StringUtils.hasText(raw)) {
			int idx = raw.indexOf("\"id\"");
			if (idx >= 0) {
				int start = raw.indexOf("cs_", idx);
				if (start >= 0) {
					int end = start;
					while (end < raw.length()) {
						char c = raw.charAt(end);
						if (!(Character.isLetterOrDigit(c) || c == '_')) {
							break;
						}
						end++;
					}
					String sessionId = raw.substring(start, end);
					try {
						return stripeCardCheckoutService.retrieveSession(sessionId);
					} catch (Exception ex) {
						log.warn("Failed to retrieve Checkout Session {}: {}", sessionId, ex.getMessage());
					}
				}
			}
		}
		log.warn("Could not deserialize Checkout Session from Stripe event type={}", event.getType());
		return null;
	}

	private RelaySubscriptionPlan requireSupportedPlan(RelaySubscriptionPlan plan) {
		if (plan != RelaySubscriptionPlan.SIX_MONTHS && plan != RelaySubscriptionPlan.YEARLY) {
			throw new BusinessRuleException("Only SIX_MONTHS and YEARLY plans are available");
		}
		return plan;
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
