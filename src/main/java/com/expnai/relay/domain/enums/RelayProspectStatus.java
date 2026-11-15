package com.expnai.relay.domain.enums;

public enum RelayProspectStatus {
	QUEUED("QUEUED"),
	SENT("SENT"),
	REPLIED("REPLIED"),
	INTERESTED("INTERESTED"),
	DECLINED("DECLINED"),
	PARTNERED("PARTNERED");

	private final String code;

	RelayProspectStatus(String code) {
		this.code = code;
	}

	public String getCode() {
		return code;
	}

	public static RelayProspectStatus fromCode(String code) {
		if (code == null) {
			return null;
		}
		for (RelayProspectStatus value : values()) {
			if (value.code.equalsIgnoreCase(code)) {
				return value;
			}
		}
		throw new IllegalArgumentException("Unknown RelayProspectStatus: " + code);
	}
}
