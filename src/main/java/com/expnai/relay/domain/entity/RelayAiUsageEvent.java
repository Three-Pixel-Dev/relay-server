package com.expnai.relay.domain.entity;

import java.math.BigDecimal;

import com.expnai.relay.common.persistence.MasterEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "relay_ai_usage_event")
public class RelayAiUsageEvent extends MasterEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "product_id", nullable = false)
	private RelayProduct product;

	@Column(name = "build_step")
	private Integer buildStep;

	@Column(name = "provider", nullable = false, length = 30)
	private String provider;

	@Column(name = "model", nullable = false, length = 80)
	private String model;

	@Column(name = "operation", nullable = false, length = 40)
	private String operation;

	@Column(name = "prompt_tokens", nullable = false)
	private int promptTokens;

	@Column(name = "completion_tokens", nullable = false)
	private int completionTokens;

	@Column(name = "cost_usd", precision = 12, scale = 6)
	private BigDecimal costUsd;
}

