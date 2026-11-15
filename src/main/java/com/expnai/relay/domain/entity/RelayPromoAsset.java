package com.expnai.relay.domain.entity;

import com.expnai.relay.common.persistence.MasterEntity;
import com.expnai.relay.domain.enums.RelayPromoAssetStatus;
import com.expnai.relay.domain.enums.RelayPromoAssetType;

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
@Table(name = "relay_promo_asset")
public class RelayPromoAsset extends MasterEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "product_id", nullable = false)
	private RelayProduct product;

	@Column(name = "asset_type", nullable = false, length = 20)
	private RelayPromoAssetType assetType;

	@Column(name = "status", nullable = false, length = 20)
	private RelayPromoAssetStatus status = RelayPromoAssetStatus.TODO;

	@Column(name = "title", length = 500)
	private String title;

	@Column(name = "duration", length = 50)
	private String duration;

	@Column(name = "url", length = 2000)
	private String url;
}
