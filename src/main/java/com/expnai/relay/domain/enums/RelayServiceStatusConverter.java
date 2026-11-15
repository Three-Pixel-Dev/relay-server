package com.expnai.relay.domain.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class RelayServiceStatusConverter implements AttributeConverter<RelayServiceStatus, String> {

	@Override
	public String convertToDatabaseColumn(RelayServiceStatus attribute) {
		return attribute == null ? null : attribute.getCode();
	}

	@Override
	public RelayServiceStatus convertToEntityAttribute(String dbData) {
		return dbData == null ? null : RelayServiceStatus.fromCode(dbData);
	}
}
