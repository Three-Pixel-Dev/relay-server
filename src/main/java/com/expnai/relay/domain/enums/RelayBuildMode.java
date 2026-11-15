package com.expnai.relay.domain.enums;

public enum RelayBuildMode {
	GUIDED("GUIDED"),
	FAST("FAST");

	private final String code;

	RelayBuildMode(String code) {
		this.code = code;
	}

	public String getCode() {
		return code;
	}

	public static RelayBuildMode fromCode(String code) {
		if (code == null) {
			return null;
		}
		for (RelayBuildMode value : values()) {
			if (value.code.equalsIgnoreCase(code)) {
				return value;
			}
		}
		throw new IllegalArgumentException("Unknown RelayBuildMode: " + code);
	}
}
