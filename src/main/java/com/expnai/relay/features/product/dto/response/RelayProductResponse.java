package com.expnai.relay.features.product.dto.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record RelayProductResponse(
		String id,
		String input,
		String mode,
		boolean forElse,
		String client,
		String partnerId,
		List<SourceItemDto> sources,
		SourceProfileDto profile,
		List<Object> orders,
		List<Object> pockets,
		Object pocket,
		String type,
		List<Object> structure,
		int words,
		Object brand,
		String stage,
		int cur,
		List<ProductStepDto> steps,
		List<ThreadMessageDto> thread,
		PublishStateDto publish,
		Map<String, Object> distribute,
		PromoteStateDto promote,
		MetricsDto metrics,
		BigDecimal spend,
		boolean cancelled,
		String created,
		String updated
) {
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SourceItemDto(
			String id,
			String kind,
			String name,
			String url,
			Long size,
			String status,
			String error
	) {
	}

	public record SourceProfileDto(
			ReadCountsDto read,
			String niche,
			String transformation,
			List<ThemeDto> themes,
			String verdict,
			String audience,
			String voice
	) {
	}

	public record ReadCountsDto(
			int videos,
			int docs,
			int passages
	) {
	}

	public record ThemeDto(
			String t,
			String d,
			String level
	) {
	}

	public record ProductStepDto(
			String status,
			String note,
			List<String> progress,
			Boolean gatePassed
	) {
	}

	public record ThreadMessageDto(
			String who,
			String text,
			String t
	) {
	}

	public record PublishStateDto(
			List<String> platforms,
			Map<String, Object> listings,
			Map<String, String> live,
			Integer step,
			String status,
			String current
	) {
	}

	public record PromoteStateDto(
			List<Object> videos,
			String status,
			boolean scheduled
	) {
	}

	public record MetricsDto(
			int sales,
			double revenue,
			int leads,
			int visits
	) {
	}
}

