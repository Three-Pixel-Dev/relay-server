package com.expnai.relay.domain.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class RelayUserRoleConverter implements AttributeConverter<RelayUserRole, String> {

	@Override
	public String convertToDatabaseColumn(RelayUserRole attribute) {
		return attribute == null ? null : attribute.getCode();
	}

	@Override
	public RelayUserRole convertToEntityAttribute(String dbData) {
		return dbData == null ? null : RelayUserRole.fromCode(dbData);
	}
}
