package com.expnai.relay.domain.entity;

import org.hibernate.annotations.ColumnTransformer;

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
@Table(name = "relay_passage")
public class RelayPassage extends MasterEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "product_id", nullable = false)
	private RelayProduct product;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "source_id", nullable = false)
	private RelaySource source;

	@Column(name = "text", nullable = false, columnDefinition = "text")
	private String text;

	@Column(name = "locator", length = 500)
	private String locator;

	@ColumnTransformer(read = "embedding::text", write = "CAST(? AS vector)")
	@Column(name = "embedding", columnDefinition = "vector(1536)")
	private String embedding;
}
