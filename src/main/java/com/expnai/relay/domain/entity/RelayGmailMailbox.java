package com.expnai.relay.domain.entity;

import java.time.Instant;

import com.expnai.relay.common.persistence.MasterEntity;
import com.expnai.relay.domain.enums.RelayGmailMailboxStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(
		name = "relay_gmail_mailbox",
		uniqueConstraints = {
				@UniqueConstraint(name = "uk_relay_gmail_mailbox_user_email", columnNames = { "user_id", "email" })
		})
public class RelayGmailMailbox extends MasterEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private RelayUser user;

	@Column(name = "email", nullable = false, length = 255)
	private String email;

	@Column(name = "google_account_id", length = 128)
	private String googleAccountId;

	@Column(name = "label", length = 255)
	private String label;

	@Column(name = "status", nullable = false, length = 30)
	private RelayGmailMailboxStatus status = RelayGmailMailboxStatus.DISCONNECTED;

	/** AES-GCM encrypted refresh token ({@code enc:vN:...}). Never plaintext. */
	@Column(name = "encrypted_refresh_token", columnDefinition = "text")
	private String encryptedRefreshToken;

	/** AES-GCM encrypted access token ({@code enc:vN:...}). */
	@Column(name = "encrypted_access_token", columnDefinition = "text")
	private String encryptedAccessToken;

	@Column(name = "access_token_expires_at")
	private Instant accessTokenExpiresAt;

	@Column(name = "encryption_key_version")
	private Integer encryptionKeyVersion;

	@Column(name = "scopes", columnDefinition = "text")
	private String scopes;

	/** Last successfully processed Gmail historyId (persisted across restarts). */
	@Column(name = "history_id", length = 64)
	private String historyId;

	@Column(name = "last_sync_at")
	private Instant lastSyncAt;

	@Column(name = "last_sync_error", columnDefinition = "text")
	private String lastSyncError;

	/** Lease owner for multi-instance sync locking (instance id). */
	@Column(name = "sync_lease_owner", length = 128)
	private String syncLeaseOwner;

	@Column(name = "sync_lease_until")
	private Instant syncLeaseUntil;
}
