package com.expnai.relay.features.gmail.oauth;

public class GmailReauthRequiredException extends RuntimeException {
	public GmailReauthRequiredException(String message) {
		super(message);
	}
}
