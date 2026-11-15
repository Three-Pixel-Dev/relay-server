package com.expnai.relay.domain.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class RelayThreadWhoConverter implements AttributeConverter<RelayThreadWho, String> {

	@Override
	public String convertToDatabaseColumn(RelayThreadWho attribute) {
		return attribute == null ? null : attribute.getCode();
	}

	@Override
	public RelayThreadWho convertToEntityAttribute(String dbData) {
		return dbData == null ? null : RelayThreadWho.fromCode(dbData);
	}
}
