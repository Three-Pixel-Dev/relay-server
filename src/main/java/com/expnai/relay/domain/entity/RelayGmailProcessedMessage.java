package com.expnai.relay.domain.entity;

import java.time.Instant;

import com.expnai.relay.common.persistence.MasterEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * Idempotency ledger for Gmail History / message processing.
 */
@Getter
@Setter
@Entity
@Table(
		name = "relay_gmail_processed_message",
		uniqueConstraints = {
				@UniqueConstraint(
						name = "uk_relay_gmail_processed_mailbox_msg",
						columnNames = { "mailbox_id", "gmail_message_id" })
		})
public class RelayGmailProcessedMessage extends MasterEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "mailbox_id", nullable = false)
	private RelayGmailMailbox mailbox;

	@Column(name = "gmail_message_id", nullable = false, length = 128)
	private String gmailMessageId;

	@Column(name = "gmail_thread_id", length = 128)
	private String gmailThreadId;

	@Column(name = "history_id", length = 64)
	private String historyId;

	@Column(name = "processed_at", nullable = false)
	private Instant processedAt = Instant.now();

	@Column(name = "outcome", length = 64)
	private String outcome;
}
