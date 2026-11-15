package com.expnai.relay.domain.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class RelayProspectStatusConverter implements AttributeConverter<RelayProspectStatus, String> {

	@Override
	public String convertToDatabaseColumn(RelayProspectStatus attribute) {
		return attribute == null ? null : attribute.getCode();
	}

	@Override
	public RelayProspectStatus convertToEntityAttribute(String dbData) {
		return dbData == null ? null : RelayProspectStatus.fromCode(dbData);
	}
}
