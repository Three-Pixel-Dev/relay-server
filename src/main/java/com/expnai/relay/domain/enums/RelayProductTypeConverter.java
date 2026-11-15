package com.expnai.relay.domain.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class RelayProductTypeConverter implements AttributeConverter<RelayProductType, String> {

	@Override
	public String convertToDatabaseColumn(RelayProductType attribute) {
		return attribute == null ? null : attribute.getCode();
	}

	@Override
	public RelayProductType convertToEntityAttribute(String dbData) {
		return dbData == null ? null : RelayProductType.fromCode(dbData);
	}
}
