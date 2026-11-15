package com.expnai.relay.domain.entity;

import java.time.Instant;

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
@Table(name = "relay_outreach_message")
public class RelayOutreachMessage extends MasterEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "prospect_id", nullable = false)
	private RelayProspect prospect;

	@Column(name = "step", nullable = false)
	private int step;

	@Column(name = "sent_at")
	private Instant sentAt;

	@Column(name = "provider_id", length = 255)
	private String providerId;

	@Column(name = "subject", length = 500)
	private String subject;

	@Column(name = "body", columnDefinition = "text")
	private String body;
}
