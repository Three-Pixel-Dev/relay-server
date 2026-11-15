package com.expnai.relay.features.product.dto.request;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RelaySourceInputDto {
	private String id;
	private String kind;
	private String name;
	private String url;
	private Long size;
}

