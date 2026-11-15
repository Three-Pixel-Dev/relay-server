package com.expnai.relay.domain.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class RelaySourceStatusConverter implements AttributeConverter<RelaySourceStatus, String> {

	@Override
	public String convertToDatabaseColumn(RelaySourceStatus attribute) {
		return attribute == null ? null : attribute.getCode();
	}

	@Override
	public RelaySourceStatus convertToEntityAttribute(String dbData) {
		return dbData == null ? null : RelaySourceStatus.fromCode(dbData);
	}
}
