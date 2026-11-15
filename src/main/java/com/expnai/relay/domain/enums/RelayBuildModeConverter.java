package com.expnai.relay.domain.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class RelayBuildModeConverter implements AttributeConverter<RelayBuildMode, String> {

	@Override
	public String convertToDatabaseColumn(RelayBuildMode attribute) {
		return attribute == null ? null : attribute.getCode();
	}

	@Override
	public RelayBuildMode convertToEntityAttribute(String dbData) {
		return dbData == null ? null : RelayBuildMode.fromCode(dbData);
	}
}
