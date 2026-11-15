package com.expnai.relay.domain.enums;

public enum RelayUserRole {
	USER("USER"),
	ADMIN("ADMIN");

	private final String code;

	RelayUserRole(String code) {
		this.code = code;
	}

	public String getCode() {
		return code;
	}

	public static RelayUserRole fromCode(String code) {
		if (code == null) {
			return null;
		}
		for (RelayUserRole value : values()) {
			if (value.code.equalsIgnoreCase(code)) {
				return value;
			}
		}
		throw new IllegalArgumentException("Unknown RelayUserRole: " + code);
	}
}
