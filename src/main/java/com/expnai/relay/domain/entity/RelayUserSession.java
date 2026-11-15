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
@Table(name = "relay_user_session")
public class RelayUserSession extends MasterEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private RelayUser user;

	@Column(name = "refresh_token_hash", nullable = false, length = 64)
	private String refreshTokenHash;

	@Column(name = "user_agent", length = 500)
	private String userAgent;

	@Column(name = "ip_address", length = 64)
	private String ipAddress;

	@Column(name = "device_label", length = 150)
	private String deviceLabel;

	@Column(name = "last_active_at", nullable = false)
	private Instant lastActiveAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;
}
