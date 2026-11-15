package com.expnai.relay.domain.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class RelayProductStageConverter implements AttributeConverter<RelayProductStage, String> {

	@Override
	public String convertToDatabaseColumn(RelayProductStage attribute) {
		return attribute == null ? null : attribute.getCode();
	}

	@Override
	public RelayProductStage convertToEntityAttribute(String dbData) {
		return dbData == null ? null : RelayProductStage.fromCode(dbData);
	}
}
