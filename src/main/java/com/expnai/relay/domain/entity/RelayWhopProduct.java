package com.expnai.relay.domain.entity;

import java.math.BigDecimal;

import com.expnai.relay.common.persistence.MasterEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "relay_whop_product")
public class RelayWhopProduct extends MasterEntity {

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "product_id", nullable = false, unique = true)
	private RelayProduct product;

	@Column(name = "external_product_id", length = 255)
	private String externalProductId;

	@Column(name = "external_company_id", length = 255)
	private String externalCompanyId;

	@Column(name = "checkout_url", length = 2000)
	private String checkoutUrl;

	@Column(name = "affiliate_rate", precision = 5, scale = 2)
	private BigDecimal affiliateRate;

	@Column(name = "partner_share", precision = 5, scale = 2)
	private BigDecimal partnerShare;

	@Column(name = "external_plan_id", length = 255)
	private String externalPlanId;

	@Column(name = "delivery_token", length = 64, unique = true)
	private String deliveryToken;
}
