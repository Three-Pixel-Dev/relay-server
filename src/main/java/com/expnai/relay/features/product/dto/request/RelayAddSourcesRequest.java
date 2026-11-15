package com.expnai.relay.features.product.dto.request;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RelayAddSourcesRequest {
	private List<RelaySourceInputDto> sources;
}

