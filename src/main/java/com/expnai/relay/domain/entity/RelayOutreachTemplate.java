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
@Table(name = "relay_outreach_template")
public class RelayOutreachTemplate extends MasterEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private RelayUser user;

	@Column(name = "subject", length = 500)
	private String subject;

	@Column(name = "body", columnDefinition = "text")
	private String body;

	@Column(name = "follow_up_1", columnDefinition = "text")
	private String followUp1;

	@Column(name = "follow_up_2", columnDefinition = "text")
	private String followUp2;

	@Column(name = "split_percent")
	private Integer splitPercent;

	@Column(name = "daily_cap")
	private Integer dailyCap;
}
