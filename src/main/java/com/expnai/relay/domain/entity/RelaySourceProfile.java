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
@Table(name = "relay_source_profile")
public class RelaySourceProfile extends MasterEntity {

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "product_id", nullable = false, unique = true)
	private RelayProduct product;

	@Column(name = "niche", length = 500)
	private String niche;

	@Column(name = "transformation", columnDefinition = "text")
	private String transformation;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "themes_json", columnDefinition = "jsonb")
	private String themesJson;

	@Column(name = "verdict", columnDefinition = "text")
	private String verdict;

	@Column(name = "audience", columnDefinition = "text")
	private String audience;

	@Column(name = "voice", columnDefinition = "text")
	private String voice;

	@Column(name = "videos_count")
	private Integer videosCount;

	@Column(name = "docs_count")
	private Integer docsCount;

	@Column(name = "passages_count")
	private Integer passagesCount;
}
