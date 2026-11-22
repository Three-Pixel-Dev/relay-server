package com.expnai.relay.features.gmail.client;

public class GmailApiException extends RuntimeException {

	private final int statusCode;
	private final boolean historyExpired;
	private final boolean rateLimited;
	private final boolean authRevoked;

	public GmailApiException(
			String message,
			int statusCode,
			boolean historyExpired,
			boolean rateLimited,
			boolean authRevoked,
			Throwable cause) {
		super(message, cause);
		this.statusCode = statusCode;
		this.historyExpired = historyExpired;
		this.rateLimited = rateLimited;
		this.authRevoked = authRevoked;
	}

	public int getStatusCode() {
		return statusCode;
	}

	public boolean isHistoryExpired() {
		return historyExpired;
	}

	public boolean isRateLimited() {
		return rateLimited;
	}

	public boolean isAuthRevoked() {
		return authRevoked;
	}
}
