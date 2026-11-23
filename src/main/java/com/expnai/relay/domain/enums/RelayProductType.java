package com.expnai.relay.domain.enums;

public enum RelayProductType {
	EBOOK("EBOOK", 11),
	WORKBOOK("WORKBOOK", 9),
	PLANNER("PLANNER", 8),
	CHECKLIST("CHECKLIST", 4),
	TEMPLATES("TEMPLATES", 8),
	MINICOURSE("MINICOURSE", 7);

	public static final int DEFAULT_TARGET_SECTIONS = 9;

	private final String code;
	private final int targetSections;

	RelayProductType(String code, int targetSections) {
		this.code = code;
		this.targetSections = targetSections;
	}

	public String getCode() {
		return code;
	}

	public int getTargetSections() {
		return targetSections;
	}

	public static int targetSectionCount(RelayProductType type) {
		return type != null ? type.getTargetSections() : DEFAULT_TARGET_SECTIONS;
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
