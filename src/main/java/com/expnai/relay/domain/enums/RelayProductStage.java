package com.expnai.relay.domain.enums;

public enum RelayProductStage {
	BUILD("BUILD"),
	PUBLISH("PUBLISH"),
	DISTRIBUTE("DISTRIBUTE"),
	PAUSED("PAUSED");

	private final String code;

	RelayProductStage(String code) {
		this.code = code;
	}

	public String getCode() {
		return code;
	}

	public static RelayProductStage fromCode(String code) {
		if (code == null) {
			return null;
		}
		for (RelayProductStage value : values()) {
			if (value.code.equalsIgnoreCase(code)) {
				return value;
			}
		}
		throw new IllegalArgumentException("Unknown RelayProductStage: " + code);
	}
}
