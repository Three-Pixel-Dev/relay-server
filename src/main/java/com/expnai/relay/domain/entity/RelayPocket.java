package com.expnai.relay.domain.entity;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
@Table(name = "relay_pocket")
public class RelayPocket extends MasterEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "product_id", nullable = false)
	private RelayProduct product;

	@Column(name = "headline", columnDefinition = "text")
	private String headline;

	@Column(name = "industry", length = 255)
	private String industry;

	@Column(name = "niche", length = 500)
	private String niche;

	@Column(name = "knowledge", columnDefinition = "text")
	private String knowledge;

	@Column(name = "problem", columnDefinition = "text")
	private String problem;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "scores_json", columnDefinition = "jsonb")
	private String scoresJson;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "evidence_json", columnDefinition = "jsonb")
	private String evidenceJson;

	@Column(name = "recommended", nullable = false)
	private boolean recommended = false;

	@Column(name = "grounded", nullable = false)
	private boolean grounded = false;
}
