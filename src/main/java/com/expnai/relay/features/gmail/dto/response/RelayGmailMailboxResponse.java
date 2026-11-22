package com.expnai.relay.features.gmail.dto.response;

public record RelayGmailMailboxResponse(
		String id,
		String email,
		String label,
		String status,
		String historyId,
		String lastSyncAt,
		String lastSyncError,
		boolean reauthRequired
) {
}
