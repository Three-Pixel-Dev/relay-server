package com.expnai.relay.domain.enums;

public enum RelayThreadWho {
	RELAY("RELAY"),
	YOU("YOU");

	private final String code;

	RelayThreadWho(String code) {
		this.code = code;
	}

	public String getCode() {
		return code;
	}

	public static RelayThreadWho fromCode(String code) {
		if (code == null) {
			return null;
		}
		for (RelayThreadWho value : values()) {
			if (value.code.equalsIgnoreCase(code)) {
				return value;
			}
		}
		throw new IllegalArgumentException("Unknown RelayThreadWho: " + code);
	}
}
