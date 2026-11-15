package com.expnai.relay.domain.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class RelaySourceKindConverter implements AttributeConverter<RelaySourceKind, String> {

	@Override
	public String convertToDatabaseColumn(RelaySourceKind attribute) {
		return attribute == null ? null : attribute.getCode();
	}

	@Override
	public RelaySourceKind convertToEntityAttribute(String dbData) {
		return dbData == null ? null : RelaySourceKind.fromCode(dbData);
	}
}
