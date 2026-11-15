package com.expnai.relay.domain.enums;

public enum RelayProductType {
	EBOOK("EBOOK"),
	WORKBOOK("WORKBOOK"),
	PLANNER("PLANNER"),
	CHECKLIST("CHECKLIST"),
	TEMPLATES("TEMPLATES"),
	MINICOURSE("MINICOURSE");

	private final String code;

	RelayProductType(String code) {
		this.code = code;
	}

	public String getCode() {
		return code;
	}

	public static RelayProductType fromCode(String code) {
		if (code == null) {
			return null;
		}
		for (RelayProductType value : values()) {
			if (value.code.equalsIgnoreCase(code)) {
				return value;
			}
		}
		throw new IllegalArgumentException("Unknown RelayProductType: " + code);
	}
}
