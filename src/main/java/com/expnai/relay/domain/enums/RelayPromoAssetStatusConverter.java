package com.expnai.relay.domain.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class RelayPromoAssetStatusConverter implements AttributeConverter<RelayPromoAssetStatus, String> {

	@Override
	public String convertToDatabaseColumn(RelayPromoAssetStatus attribute) {
		return attribute == null ? null : attribute.getCode();
	}

	@Override
	public RelayPromoAssetStatus convertToEntityAttribute(String dbData) {
		return dbData == null ? null : RelayPromoAssetStatus.fromCode(dbData);
	}
}
