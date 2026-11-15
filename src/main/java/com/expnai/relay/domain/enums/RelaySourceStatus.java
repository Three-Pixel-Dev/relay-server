package com.expnai.relay.domain.enums;

public enum RelaySourceStatus {
	PENDING("PENDING"),
	READING("READING"),
	READY("READY"),
	SKIPPED("SKIPPED"),
	FAILED("FAILED");

	private final String code;

	RelaySourceStatus(String code) {
		this.code = code;
	}

	public String getCode() {
		return code;
	}

	public static RelaySourceStatus fromCode(String code) {
		if (code == null) {
			return null;
		}
		for (RelaySourceStatus value : values()) {
			if (value.code.equalsIgnoreCase(code)) {
				return value;
			}
		}
		throw new IllegalArgumentException("Unknown RelaySourceStatus: " + code);
	}
}
