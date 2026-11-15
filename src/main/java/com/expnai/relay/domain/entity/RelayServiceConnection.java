package com.expnai.relay.domain.entity;

import com.expnai.relay.common.persistence.MasterEntity;
import com.expnai.relay.domain.enums.RelayServiceStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "relay_service_connection")
public class RelayServiceConnection extends MasterEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private RelayUser user;

	@Column(name = "service_id", nullable = false, length = 50)
	private String serviceId;

	@Column(name = "label", length = 255)
	private String label;

	@Column(name = "status", nullable = false, length = 20)
	private RelayServiceStatus status = RelayServiceStatus.DISCONNECTED;

	@Column(name = "encrypted_credential", columnDefinition = "text")
	private String encryptedCredential;

	@Column(name = "scopes", columnDefinition = "text")
	private String scopes;
}
