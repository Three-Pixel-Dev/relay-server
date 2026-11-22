package com.expnai.relay.domain.enums;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class RelayGmailMailboxStatusConverter implements AttributeConverter<RelayGmailMailboxStatus, String> {

	@Override
	public String convertToDatabaseColumn(RelayGmailMailboxStatus attribute) {
		return attribute != null ? attribute.getCode() : null;
	}

	@Override
	public RelayGmailMailboxStatus convertToEntityAttribute(String dbData) {
		return RelayGmailMailboxStatus.fromCode(dbData);
	}
}
