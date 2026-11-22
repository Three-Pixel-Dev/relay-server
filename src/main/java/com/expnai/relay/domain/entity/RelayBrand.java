package com.expnai.relay.domain.entity;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
@Table(name = "relay_brand")
public class RelayBrand extends MasterEntity {

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "product_id", nullable = false, unique = true)
	private RelayProduct product;

	@Column(name = "title", length = 500)
	private String title;

	@Column(name = "palette_index")
	private Integer paletteIndex;

	@Column(name = "type_pair", length = 100)
	private String typePair;

	@Column(name = "cover_variant")
	private Integer coverVariant;

	@Column(name = "logo_url", length = 2000)
	private String logoUrl;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "cover_image_keys_json", columnDefinition = "jsonb")
	private String coverImageKeysJson;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "page_graphics_json", columnDefinition = "jsonb")
	private String pageGraphicsJson;
}
