package com.expnai.relay.domain.entity;

import java.math.BigDecimal;

import com.expnai.relay.common.persistence.MasterEntity;
import com.expnai.relay.domain.enums.RelayListingStatus;

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
@Table(name = "relay_listing")
public class RelayListing extends MasterEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "product_id", nullable = false)
	private RelayProduct product;

	@Column(name = "platform", nullable = false, length = 50)
	private String platform;

	@Column(name = "title", length = 500)
	private String title;

	@Column(name = "tags", columnDefinition = "text")
	private String tags;

	@Column(name = "description", columnDefinition = "text")
	private String description;

	@Column(name = "price", precision = 12, scale = 2)
	private BigDecimal price;

	@Column(name = "status", nullable = false, length = 20)
	private RelayListingStatus status = RelayListingStatus.DRAFT;

	@Column(name = "external_id", length = 255)
	private String externalId;

	@Column(name = "url", length = 2000)
	private String url;
}
