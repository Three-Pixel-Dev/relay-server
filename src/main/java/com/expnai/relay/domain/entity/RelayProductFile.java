package com.expnai.relay.domain.entity;

import com.expnai.relay.common.persistence.MasterEntity;

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
@Table(name = "relay_product_file")
public class RelayProductFile extends MasterEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "product_id", nullable = false)
	private RelayProduct product;

	@Column(name = "file_type", nullable = false, length = 50)
	private String fileType;

	@Column(name = "version", nullable = false)
	private int version = 1;

	@Column(name = "url", length = 2000)
	private String url;
}
