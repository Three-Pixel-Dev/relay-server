package com.expnai.relay.domain.entity;

import java.time.Instant;

import com.expnai.relay.common.persistence.MasterEntity;
import com.expnai.relay.domain.enums.RelaySubscriptionPlan;
import com.expnai.relay.domain.enums.RelaySubscriptionStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "relay_subscription")
public class RelaySubscription extends MasterEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private RelayUser user;

	@Enumerated(EnumType.STRING)
	@Column(name = "plan", nullable = false, length = 20)
	private RelaySubscriptionPlan plan;

	/** No trial — new paid subscriptions start ACTIVE (payment provider TBD). */
	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private RelaySubscriptionStatus status = RelaySubscriptionStatus.ACTIVE;

	@Column(name = "stripe_customer_id", length = 255)
	private String stripeCustomerId;

	@Column(name = "stripe_subscription_id", length = 255)
	private String stripeSubscriptionId;

	@Column(name = "current_period_start")
	private Instant currentPeriodStart;

	@Column(name = "current_period_end")
	private Instant currentPeriodEnd;

	@Column(name = "cancel_at_period_end", nullable = false)
	private boolean cancelAtPeriodEnd = false;

	@Column(name = "canceled_at")
	private Instant canceledAt;
}
