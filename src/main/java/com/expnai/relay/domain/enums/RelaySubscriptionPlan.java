package com.expnai.relay.domain.enums;

public enum RelaySubscriptionPlan {
	MONTHLY("MONTHLY"),
	YEARLY("YEARLY");

	private final String code;

	RelaySubscriptionPlan(String code) {
		this.code = code;
	}

	public String getCode() {
		return code;
	}

	public static RelaySubscriptionPlan fromCode(String code) {
		if (code == null) {
			return null;
		}
		for (RelaySubscriptionPlan value : values()) {
			if (value.code.equalsIgnoreCase(code)) {
				return value;
			}
		}
		throw new IllegalArgumentException("Unknown RelaySubscriptionPlan: " + code);
	}
}
