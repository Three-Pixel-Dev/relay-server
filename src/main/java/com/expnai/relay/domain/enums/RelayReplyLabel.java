package com.expnai.relay.domain.enums;

public enum RelayReplyLabel {
	INTERESTED("INTERESTED"),
	DECLINED("DECLINED"),
	OTHER("OTHER"),
	UNKNOWN("UNKNOWN");

	private final String code;

	RelayReplyLabel(String code) {
		this.code = code;
	}

	public String getCode() {
		return code;
	}

	public static RelayReplyLabel fromCode(String code) {
		if (code == null) {
			return null;
		}
		for (RelayReplyLabel value : values()) {
			if (value.code.equalsIgnoreCase(code)) {
				return value;
			}
		}
		throw new IllegalArgumentException("Unknown RelayReplyLabel: " + code);
	}
}
