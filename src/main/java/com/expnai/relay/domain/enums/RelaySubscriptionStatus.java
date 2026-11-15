package com.expnai.relay.domain.enums;

public enum RelaySubscriptionStatus {
	TRIALING("TRIALING"),
	ACTIVE("ACTIVE"),
	PAST_DUE("PAST_DUE"),
	CANCELED("CANCELED"),
	EXPIRED("EXPIRED");

	private final String code;

	RelaySubscriptionStatus(String code) {
		this.code = code;
	}

	public String getCode() {
		return code;
	}

	public static RelaySubscriptionStatus fromCode(String code) {
		if (code == null) {
			return null;
		}
		for (RelaySubscriptionStatus value : values()) {
			if (value.code.equalsIgnoreCase(code)) {
				return value;
			}
		}
		throw new IllegalArgumentException("Unknown RelaySubscriptionStatus: " + code);
	}
}
