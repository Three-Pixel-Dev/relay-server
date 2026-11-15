package com.expnai.relay.domain.enums;

public enum RelayPromoAssetStatus {
	TODO("TODO"),
	RUNNING("RUNNING"),
	GATE("GATE"),
	REVIEW("REVIEW"),
	DONE("DONE"),
	SCHEDULED("SCHEDULED");

	private final String code;

	RelayPromoAssetStatus(String code) {
		this.code = code;
	}

	public String getCode() {
		return code;
	}

	public static RelayPromoAssetStatus fromCode(String code) {
		if (code == null) {
			return null;
		}
		for (RelayPromoAssetStatus value : values()) {
			if (value.code.equalsIgnoreCase(code)) {
				return value;
			}
		}
		throw new IllegalArgumentException("Unknown RelayPromoAssetStatus: " + code);
	}
}
