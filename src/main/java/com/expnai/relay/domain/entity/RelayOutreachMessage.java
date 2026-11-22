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

@Getter
@Setter
@Entity
@Table(
		name = "relay_outreach_message",
		uniqueConstraints = {
				@UniqueConstraint(name = "uk_relay_outreach_message_idempotency", columnNames = { "idempotency_key" })
		})
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

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "mailbox_id")
	private RelayGmailMailbox mailbox;

	@Column(name = "gmail_message_id", length = 128)
	private String gmailMessageId;

	@Column(name = "gmail_thread_id", length = 128)
	private String gmailThreadId;

	/** PENDING | SENT | CANCELLED | FAILED */
	@Column(name = "send_status", length = 20)
	private String sendStatus = "SENT";

	@Column(name = "scheduled_at")
	private Instant scheduledAt;

	@Column(name = "cancelled_at")
	private Instant cancelledAt;

	@Column(name = "cancel_reason", length = 255)
	private String cancelReason;

	/** Idempotency key to prevent duplicate sends on retry. */
	@Column(name = "idempotency_key", length = 128)
	private String idempotencyKey;
}
