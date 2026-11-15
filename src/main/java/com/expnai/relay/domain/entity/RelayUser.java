package com.expnai.relay.domain.entity;

import com.expnai.relay.common.persistence.MasterEntity;
import com.expnai.relay.domain.enums.RelayUserRole;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "relay_user")
public class RelayUser extends MasterEntity {

	@Column(name = "email", length = 255)
	private String email;

	/** Null for Google-only accounts. */
	@Column(name = "password_hash", length = 255)
	private String passwordHash;

	@Column(name = "name", length = 150)
	private String name;

	@Column(name = "role", nullable = false, length = 20)
	private RelayUserRole role = RelayUserRole.USER;

	@Column(name = "onboard_seen", nullable = false)
	private boolean onboardSeen = false;

	@Column(name = "token_version", nullable = false)
	private long tokenVersion = 0L;

	/** Google subject (`sub`) from verified ID token. */
	@Column(name = "google_id", length = 100, unique = true)
	private String googleId;

	@Column(name = "avatar_url", length = 2000)
	private String avatarUrl;
}
