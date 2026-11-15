package com.expnai.relay.domain.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class RelayPromoAssetTypeConverter implements AttributeConverter<RelayPromoAssetType, String> {

	@Override
	public String convertToDatabaseColumn(RelayPromoAssetType attribute) {
		return attribute == null ? null : attribute.getCode();
	}

	@Override
	public RelayPromoAssetType convertToEntityAttribute(String dbData) {
		return dbData == null ? null : RelayPromoAssetType.fromCode(dbData);
	}
}
