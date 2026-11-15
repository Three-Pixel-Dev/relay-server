package com.expnai.relay.domain.enums;

public enum RelaySectionStatus {
	TODO("TODO"),
	LIVE("LIVE"),
	DONE("DONE");

	private final String code;

	RelaySectionStatus(String code) {
		this.code = code;
	}

	public String getCode() {
		return code;
	}

	public static RelaySectionStatus fromCode(String code) {
		if (code == null) {
			return null;
		}
		for (RelaySectionStatus value : values()) {
			if (value.code.equalsIgnoreCase(code)) {
				return value;
			}
		}
		throw new IllegalArgumentException("Unknown RelaySectionStatus: " + code);
	}
}
