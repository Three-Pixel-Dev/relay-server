package com.expnai.relay.domain.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class RelayProspectOriginConverter implements AttributeConverter<RelayProspectOrigin, String> {

	@Override
	public String convertToDatabaseColumn(RelayProspectOrigin attribute) {
		return attribute == null ? null : attribute.getCode();
	}

	@Override
	public RelayProspectOrigin convertToEntityAttribute(String dbData) {
		return dbData == null ? null : RelayProspectOrigin.fromCode(dbData);
	}
}
