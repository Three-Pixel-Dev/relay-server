package com.expnai.relay.domain.entity;

import com.expnai.relay.common.persistence.MasterEntity;
import com.expnai.relay.domain.enums.RelayProspectOrigin;
import com.expnai.relay.domain.enums.RelayProspectStatus;

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
@Table(name = "relay_prospect")
public class RelayProspect extends MasterEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private RelayUser user;

	@Column(name = "name", length = 255)
	private String name;

	@Column(name = "handle", length = 255)
	private String handle;

	@Column(name = "platform", length = 50)
	private String platform;

	@Column(name = "followers", length = 50)
	private String followers;

	@Column(name = "niche", length = 500)
	private String niche;

	@Column(name = "list_mention", columnDefinition = "text")
	private String listMention;

	@Column(name = "list_size", length = 50)
	private String listSize;

	@Column(name = "email", length = 255)
	private String email;

	@Column(name = "product_note", columnDefinition = "text")
	private String productNote;

	@Column(name = "url", length = 2000)
	private String url;

	@Column(name = "origin", nullable = false, length = 20)
	private RelayProspectOrigin origin = RelayProspectOrigin.SCOUT;

	@Column(name = "status", nullable = false, length = 20)
	private RelayProspectStatus status = RelayProspectStatus.QUEUED;

	@Column(name = "outreach_step")
	private Integer outreachStep;
}
