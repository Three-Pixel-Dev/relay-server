package com.expnai.relay.domain.entity;

import com.expnai.relay.common.persistence.MasterEntity;
import com.expnai.relay.domain.enums.RelaySectionStatus;

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
@Table(name = "relay_section")
public class RelaySection extends MasterEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "product_id", nullable = false)
	private RelayProduct product;

	@Column(name = "title", length = 500)
	private String title;

	@Column(name = "moment", columnDefinition = "text")
	private String moment;

	@Column(name = "text", columnDefinition = "text")
	private String text;

	@Column(name = "words")
	private Integer words;

	@Column(name = "status", nullable = false, length = 20)
	private RelaySectionStatus status = RelaySectionStatus.TODO;

	@Column(name = "version", nullable = false)
	private int version = 1;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder = 0;
}
