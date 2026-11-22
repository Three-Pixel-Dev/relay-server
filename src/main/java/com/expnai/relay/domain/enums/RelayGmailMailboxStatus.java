package com.expnai.relay.domain.enums;

public enum RelayGmailMailboxStatus {
	CONNECTED("CONNECTED"),
	REAUTH_REQUIRED("REAUTH_REQUIRED"),
	DISCONNECTED("DISCONNECTED"),
	ERROR("ERROR");

	private final String code;

	RelayGmailMailboxStatus(String code) {
		this.code = code;
	}

	public String getCode() {
		return code;
	}

	public static RelayGmailMailboxStatus fromCode(String code) {
		if (code == null) {
			return null;
		}
		for (RelayGmailMailboxStatus value : values()) {
			if (value.code.equalsIgnoreCase(code)) {
				return value;
			}
		}
		throw new IllegalArgumentException("Unknown RelayGmailMailboxStatus: " + code);
	}
}
