package com.expnai.relay.domain.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class RelayReplyLabelConverter implements AttributeConverter<RelayReplyLabel, String> {

	@Override
	public String convertToDatabaseColumn(RelayReplyLabel attribute) {
		return attribute == null ? null : attribute.getCode();
	}

	@Override
	public RelayReplyLabel convertToEntityAttribute(String dbData) {
		return dbData == null ? null : RelayReplyLabel.fromCode(dbData);
	}
}
