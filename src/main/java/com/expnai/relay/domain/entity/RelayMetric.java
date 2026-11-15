package com.expnai.relay.domain.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

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
@Table(name = "relay_metric")
public class RelayMetric extends MasterEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "product_id", nullable = false)
	private RelayProduct product;

	@Column(name = "source", length = 255)
	private String source;

	@Column(name = "metric_date", nullable = false)
	private LocalDate metricDate;

	@Column(name = "visits")
	private Integer visits;

	@Column(name = "clicks")
	private Integer clicks;

	@Column(name = "leads")
	private Integer leads;

	@Column(name = "sales")
	private Integer sales;

	@Column(name = "revenue", precision = 12, scale = 2)
	private BigDecimal revenue;
}
