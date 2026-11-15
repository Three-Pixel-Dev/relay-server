package com.expnai.relay.domain.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class RelaySubscriptionPlanConverter implements AttributeConverter<RelaySubscriptionPlan, String> {

	@Override
	public String convertToDatabaseColumn(RelaySubscriptionPlan attribute) {
		return attribute == null ? null : attribute.getCode();
	}

	@Override
	public RelaySubscriptionPlan convertToEntityAttribute(String dbData) {
		return dbData == null ? null : RelaySubscriptionPlan.fromCode(dbData);
	}
}
