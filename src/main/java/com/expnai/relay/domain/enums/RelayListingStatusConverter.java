package com.expnai.relay.domain.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class RelayListingStatusConverter implements AttributeConverter<RelayListingStatus, String> {

	@Override
	public String convertToDatabaseColumn(RelayListingStatus attribute) {
		return attribute == null ? null : attribute.getCode();
	}

	@Override
	public RelayListingStatus convertToEntityAttribute(String dbData) {
		return dbData == null ? null : RelayListingStatus.fromCode(dbData);
	}
}
