package com.expnai.relay.domain.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class RelaySubscriptionStatusConverter implements AttributeConverter<RelaySubscriptionStatus, String> {

	@Override
	public String convertToDatabaseColumn(RelaySubscriptionStatus attribute) {
		return attribute == null ? null : attribute.getCode();
	}

	@Override
	public RelaySubscriptionStatus convertToEntityAttribute(String dbData) {
		return dbData == null ? null : RelaySubscriptionStatus.fromCode(dbData);
	}
}
