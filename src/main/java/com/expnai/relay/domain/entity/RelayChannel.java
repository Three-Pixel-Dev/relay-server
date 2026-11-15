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
@Table(name = "relay_channel")
public class RelayChannel extends MasterEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "product_id", nullable = false)
	private RelayProduct product;

	@Column(name = "channel_type", nullable = false, length = 50)
	private String channelType;

	@Column(name = "enabled", nullable = false)
	private boolean enabled = false;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "settings_json", columnDefinition = "jsonb")
	private String settingsJson;

	@Column(name = "week_number")
	private Integer weekNumber;

	@Column(name = "clicks")
	private Integer clicks;
}
