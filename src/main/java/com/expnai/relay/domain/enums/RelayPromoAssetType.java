package com.expnai.relay.domain.enums;

public enum RelayPromoAssetType {
	VIDEO("VIDEO"),
	IMAGE("IMAGE"),
	COPY("COPY");

	private final String code;

	RelayPromoAssetType(String code) {
		this.code = code;
	}

	public String getCode() {
		return code;
	}

	public static RelayPromoAssetType fromCode(String code) {
		if (code == null) {
			return null;
		}
		for (RelayPromoAssetType value : values()) {
			if (value.code.equalsIgnoreCase(code)) {
				return value;
			}
		}
		throw new IllegalArgumentException("Unknown RelayPromoAssetType: " + code);
	}
}
