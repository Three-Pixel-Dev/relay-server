package com.expnai.relay.domain.entity;

import com.expnai.relay.common.persistence.MasterEntity;
import com.expnai.relay.domain.enums.RelaySourceKind;
import com.expnai.relay.domain.enums.RelaySourceStatus;

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
@Table(name = "relay_source")
public class RelaySource extends MasterEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "product_id", nullable = false)
	private RelayProduct product;

	@Column(name = "kind", nullable = false, length = 20)
	private RelaySourceKind kind;

	@Column(name = "name", length = 500)
	private String name;

	@Column(name = "url", length = 2000)
	private String url;

	@Column(name = "file_size")
	private Long fileSize;

	@Column(name = "status", nullable = false, length = 20)
	private RelaySourceStatus status = RelaySourceStatus.PENDING;

	@Column(name = "storage_key", length = 1000)
	private String storageKey;

	@Column(name = "error_message", columnDefinition = "text")
	private String errorMessage;
}
