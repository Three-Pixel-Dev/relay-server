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
@Table(name = "relay_audit_event")
public class RelayAuditEvent extends MasterEntity {

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id")
	private RelayUser user;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "product_id")
	private RelayProduct product;

	@Column(name = "action", nullable = false, length = 100)
	private String action;

	@Column(name = "detail", columnDefinition = "text")
	private String detail;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "payload_json", columnDefinition = "jsonb")
	private String payloadJson;
}
