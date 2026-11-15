package com.expnai.relay.domain.entity;

import com.expnai.relay.common.persistence.MasterEntity;
import com.expnai.relay.domain.enums.RelayReplyLabel;

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
@Table(name = "relay_reply")
public class RelayReply extends MasterEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "prospect_id", nullable = false)
	private RelayProspect prospect;

	@Column(name = "text", columnDefinition = "text")
	private String text;

	@Column(name = "label", length = 20)
	private RelayReplyLabel label = RelayReplyLabel.UNKNOWN;

	@Column(name = "corrected", nullable = false)
	private boolean corrected = false;
}
