package com.expnai.relay.features.subscription.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.expnai.relay.common.exception.BusinessRuleException;
import com.expnai.relay.domain.enums.RelaySubscriptionPlan;
import com.expnai.relay.domain.entity.RelayUser;
import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;

/**
 * Stripe Embedded Checkout (card only) for Relay plan purchases — same pattern as Payni
 * {@code StripeCardPaymentProvider}: Mode.PAYMENT, UiMode.EMBEDDED, CARD only.
 */
@Service
public class RelayStripeCardCheckoutService {

	private static final Logger log = LoggerFactory.getLogger(RelayStripeCardCheckoutService.class);

	@Value("${stripe.secret-key:}")
	private String secretKey;

	@Value("${stripe.publishable-key:}")
	private String publishableKey;

	@Value("${stripe.webhook-secret:}")
	private String webhookSecret;

	@Value("${relay.billing.currency:usd}")
	private String currency;

	@Value("${relay.billing.six-months-price-usd:99}")
	private BigDecimal sixMonthsPriceUsd;

	@Value("${relay.billing.yearly-price-usd:149}")
	private BigDecimal yearlyPriceUsd;

	public boolean isConfigured() {
		return StringUtils.hasText(secretKey) && StringUtils.hasText(publishableKey);
	}

	public SessionCreateResult createEmbeddedCheckout(RelayUser user, RelaySubscriptionPlan plan) {
		if (!isConfigured()) {
			throw new BusinessRuleException("Stripe is not configured. Set STRIPE_SECRET_KEY and STRIPE_PUBLISHABLE_KEY.");
		}
		applyApiKey();
		BigDecimal price = priceFor(plan);
		long amountMinor = toMinorUnits(price);
		String planName = plan == RelaySubscriptionPlan.YEARLY ? "Relay Yearly" : "Relay 6 months";
		try {
			SessionCreateParams.LineItem.PriceData.ProductData product =
					SessionCreateParams.LineItem.PriceData.ProductData.builder()
							.setName(planName)
							.setDescription("Relay subscription — no trial. Card payment only.")
							.build();
			SessionCreateParams.LineItem.PriceData priceData =
					SessionCreateParams.LineItem.PriceData.builder()
							.setCurrency(currency.trim().toLowerCase())
							.setUnitAmount(amountMinor)
							.setProductData(product)
							.build();
			SessionCreateParams params = SessionCreateParams.builder()
					.setMode(SessionCreateParams.Mode.PAYMENT)
					.setUiMode(SessionCreateParams.UiMode.EMBEDDED)
					.setRedirectOnCompletion(SessionCreateParams.RedirectOnCompletion.NEVER)
					.addPaymentMethodType(SessionCreateParams.PaymentMethodType.CARD)
					.setClientReferenceId("relay-user-" + user.getId())
					.setCustomerEmail(user.getEmail())
					.putMetadata("relay_kind", "subscription")
					.putMetadata("user_id", String.valueOf(user.getId()))
					.putMetadata("plan", plan.name())
					.putMetadata("amount_usd", price.toPlainString())
					.addLineItem(SessionCreateParams.LineItem.builder()
							.setQuantity(1L)
							.setPriceData(priceData)
							.build())
					.build();
			Session session = Session.create(params);
			if (!StringUtils.hasText(session.getClientSecret())) {
				throw new BusinessRuleException("Stripe Checkout Session did not return a client secret.");
			}
			log.info("Stripe Embedded Checkout created session={} user={} plan={}",
					session.getId(), user.getId(), plan);
			return new SessionCreateResult(
					session.getId(),
					session.getClientSecret(),
					publishableKey.trim(),
					plan.name());
		} catch (StripeException ex) {
			log.warn("Stripe Checkout create failed: {}", ex.getMessage());
			throw new BusinessRuleException(
					"Stripe Checkout failed: " + (ex.getMessage() != null ? ex.getMessage() : "provider error"));
		}
	}

	public Session retrieveSession(String sessionId) {
		if (!StringUtils.hasText(sessionId)) {
			throw new BusinessRuleException("sessionId is required");
		}
		applyApiKey();
		try {
			return Session.retrieve(sessionId.trim());
		} catch (StripeException ex) {
			throw new BusinessRuleException("Could not retrieve Stripe session: " + ex.getMessage());
		}
	}

	public Event constructEvent(String rawBody, String signatureHeader) {
		if (!StringUtils.hasText(webhookSecret)) {
			throw new BusinessRuleException("STRIPE_WEBHOOK_SECRET is not configured.");
		}
		if (!StringUtils.hasText(signatureHeader)) {
			throw new BusinessRuleException("Missing Stripe-Signature header.");
		}
		try {
			return Webhook.constructEvent(rawBody, signatureHeader, webhookSecret.trim());
		} catch (SignatureVerificationException ex) {
			throw new BusinessRuleException("Invalid Stripe signature.");
		}
	}

	public PaidCheckoutPayload requirePaidPayload(Session session) {
		if (session == null) {
			throw new BusinessRuleException("Checkout session not found.");
		}
		String status = session.getPaymentStatus();
		if (!"paid".equalsIgnoreCase(status)) {
			throw new BusinessRuleException("Payment is not completed yet (status=" + status + ").");
		}
		Map<String, String> metadata = session.getMetadata();
		if (metadata == null) {
			throw new BusinessRuleException("Checkout session is missing Relay metadata.");
		}
		String userIdRaw = metadata.get("user_id");
		String planRaw = metadata.get("plan");
		if (!StringUtils.hasText(userIdRaw) || !StringUtils.hasText(planRaw)) {
			throw new BusinessRuleException("Checkout session metadata is incomplete.");
		}
		RelaySubscriptionPlan plan;
		try {
			plan = RelaySubscriptionPlan.valueOf(planRaw.trim());
		} catch (IllegalArgumentException ex) {
			throw new BusinessRuleException("Unknown plan in checkout metadata: " + planRaw);
		}
		Long userId;
		try {
			userId = Long.parseLong(userIdRaw.trim());
		} catch (NumberFormatException ex) {
			throw new BusinessRuleException("Invalid user_id in checkout metadata.");
		}
		String customerId = session.getCustomer();
		return new PaidCheckoutPayload(userId, plan, session.getId(), customerId);
	}

	private BigDecimal priceFor(RelaySubscriptionPlan plan) {
		return plan == RelaySubscriptionPlan.YEARLY ? yearlyPriceUsd : sixMonthsPriceUsd;
	}

	private long toMinorUnits(BigDecimal amount) {
		return amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
	}

	private void applyApiKey() {
		if (!StringUtils.hasText(secretKey)) {
			throw new BusinessRuleException("STRIPE_SECRET_KEY is not configured.");
		}
		Stripe.apiKey = secretKey.trim();
	}

	public record SessionCreateResult(
			String sessionId,
			String clientSecret,
			String publishableKey,
			String plan) {
	}

	public record PaidCheckoutPayload(
			Long userId,
			RelaySubscriptionPlan plan,
			String checkoutSessionId,
			String stripeCustomerId) {
	}
}
