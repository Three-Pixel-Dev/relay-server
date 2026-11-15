package com.expnai.relay.domain.entity;

import com.expnai.relay.common.persistence.MasterEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "relay_partnership")
public class RelayPartnership extends MasterEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private RelayUser user;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "prospect_id", nullable = false, unique = true)
	private RelayProspect prospect;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "product_id")
	private RelayProduct product;

	@Column(name = "split_percent", nullable = false)
	private int splitPercent = 50;
}
