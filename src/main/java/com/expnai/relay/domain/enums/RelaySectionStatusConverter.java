package com.expnai.relay.domain.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class RelaySectionStatusConverter implements AttributeConverter<RelaySectionStatus, String> {

	@Override
	public String convertToDatabaseColumn(RelaySectionStatus attribute) {
		return attribute == null ? null : attribute.getCode();
	}

	@Override
	public RelaySectionStatus convertToEntityAttribute(String dbData) {
		return dbData == null ? null : RelaySectionStatus.fromCode(dbData);
	}
}
