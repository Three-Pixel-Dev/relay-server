package com.expnai.relay.features.product.dto.request;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RelayCreateProductRequest {
	private String input;
	private String mode;
	private Boolean forElse;
	private String client;
	private String partnerId;
	private List<RelaySourceInputDto> sources;
	private Integer pendingFilesCount;
}

