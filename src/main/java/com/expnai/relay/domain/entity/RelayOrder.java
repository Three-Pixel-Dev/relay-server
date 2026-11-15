package com.expnai.relay.domain.entity;

import java.math.BigDecimal;
import java.time.Instant;

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
@Table(name = "relay_order")
public class RelayOrder extends MasterEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "product_id", nullable = false)
	private RelayProduct product;

	@Column(name = "amount", nullable = false, precision = 12, scale = 2)
	private BigDecimal amount;

	@Column(name = "source", length = 255)
	private String source;

	@Column(name = "external_id", length = 255)
	private String externalId;

	@Column(name = "ordered_at", nullable = false)
	private Instant orderedAt;
}
