package com.expnai.relay.domain.enums;

public enum RelayListingStatus {
	DRAFT("DRAFT"),
	APPROVED("APPROVED"),
	LIVE("LIVE"),
	SKIPPED("SKIPPED"),
	FAILED("FAILED");

	private final String code;

	RelayListingStatus(String code) {
		this.code = code;
	}

	public String getCode() {
		return code;
	}

	public static RelayListingStatus fromCode(String code) {
		if (code == null) {
			return null;
		}
		for (RelayListingStatus value : values()) {
			if (value.code.equalsIgnoreCase(code)) {
				return value;
			}
		}
		throw new IllegalArgumentException("Unknown RelayListingStatus: " + code);
	}
}
