package com.expnai.relay.domain.enums;

public enum RelayProspectOrigin {
	SCOUT("SCOUT"),
	OWN("OWN");

	private final String code;

	RelayProspectOrigin(String code) {
		this.code = code;
	}

	public String getCode() {
		return code;
	}

	public static RelayProspectOrigin fromCode(String code) {
		if (code == null) {
			return null;
		}
		for (RelayProspectOrigin value : values()) {
			if (value.code.equalsIgnoreCase(code)) {
				return value;
			}
		}
		throw new IllegalArgumentException("Unknown RelayProspectOrigin: " + code);
	}
}
