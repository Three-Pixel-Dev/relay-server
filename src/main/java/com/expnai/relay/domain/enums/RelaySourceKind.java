package com.expnai.relay.domain.enums;

public enum RelaySourceKind {
	FILE("FILE"),
	YOUTUBE("YOUTUBE"),
	INSTAGRAM("INSTAGRAM"),
	WEB("WEB");

	private final String code;

	RelaySourceKind(String code) {
		this.code = code;
	}

	public String getCode() {
		return code;
	}

	public static RelaySourceKind fromCode(String code) {
		if (code == null) {
			return null;
		}
		for (RelaySourceKind value : values()) {
			if (value.code.equalsIgnoreCase(code)) {
				return value;
			}
		}
		throw new IllegalArgumentException("Unknown RelaySourceKind: " + code);
	}
}
