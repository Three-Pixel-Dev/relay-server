package com.expnai.relay.domain.enums;

public enum RelayServiceStatus {
	DISCONNECTED("DISCONNECTED"),
	CONNECTED("CONNECTED"),
	ERROR("ERROR");

	private final String code;

	RelayServiceStatus(String code) {
		this.code = code;
	}

	public String getCode() {
		return code;
	}

	public static RelayServiceStatus fromCode(String code) {
		if (code == null) {
			return null;
		}
		for (RelayServiceStatus value : values()) {
			if (value.code.equalsIgnoreCase(code)) {
				return value;
			}
		}
		throw new IllegalArgumentException("Unknown RelayServiceStatus: " + code);
	}
}
