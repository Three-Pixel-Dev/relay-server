package com.expnai.relay.domain.entity;

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
@Table(name = "relay_citation")
public class RelayCitation extends MasterEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "section_id", nullable = false)
	private RelaySection section;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "passage_id")
	private RelayPassage passage;

	@Column(name = "locator", length = 500)
	private String locator;
}
