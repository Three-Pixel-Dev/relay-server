package com.expnai.relay.domain.entity;

import java.time.Instant;

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
@Table(name = "relay_batch")
public class RelayBatch extends MasterEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "channel_id", nullable = false)
	private RelayChannel channel;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "items_json", columnDefinition = "jsonb")
	private String itemsJson;

	@Column(name = "approved_at")
	private Instant approvedAt;

	@Column(name = "posted_at")
	private Instant postedAt;
}
