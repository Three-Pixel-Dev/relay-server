package com.expnai.relay.features.product.dto.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record RelayProductResponse(
		String id,
		String name,
		String input,
		String mode,
		boolean forElse,
		String client,
		String partnerId,
		List<SourceItemDto> sources,
		SourceProfileDto profile,
		List<Object> orders,
		List<PocketDto> pockets,
		PocketDto pocket,
		String type,
		Map<String, TypePriceSuggestionDto> typePriceSuggestions,
		List<SectionDto> structure,
		int words,
		BrandDto brand,
		List<ProductFileDto> files,
		String stage,
		int cur,
		List<ProductStepDto> steps,
		List<ThreadMessageDto> thread,
		PublishStateDto publish,
		Map<String, ChannelStateDto> distribute,
		PromoteStateDto promote,
		MetricsDto metrics,
		BigDecimal spend,
		BigDecimal remainingEstimate,
		boolean pricingAvailable,
		String pricingMessage,
		String qualityWarning,
		boolean cancelled,
		String created,
		String updated
) {
	public RelayProductResponse withCommerce(List<Object> newOrders, PublishStateDto newPublish, MetricsDto newMetrics) {
		return new RelayProductResponse(
				id, name, input, mode, forElse, client, partnerId, sources, profile,
				newOrders, pockets, pocket, type, typePriceSuggestions, structure, words, brand, files, stage, cur, steps, thread,
				newPublish, distribute, promote, newMetrics, spend, remainingEstimate, pricingAvailable,
				pricingMessage, qualityWarning, cancelled, created, updated);
	}

	public RelayProductResponse withDistributePromote(
			Map<String, ChannelStateDto> newDistribute,
			PromoteStateDto newPromote,
			int leads,
			int visits) {
		MetricsDto base = metrics != null ? metrics : new MetricsDto(0, 0.0, 0, 0);
		MetricsDto merged = new MetricsDto(base.sales(), base.revenue(), leads, visits);
		return new RelayProductResponse(
				id, name, input, mode, forElse, client, partnerId, sources, profile,
				orders, pockets, pocket, type, typePriceSuggestions, structure, words, brand, files, stage, cur, steps, thread,
				publish, newDistribute, newPromote, merged, spend, remainingEstimate, pricingAvailable,
				pricingMessage, qualityWarning, cancelled, created, updated);
	}

	public record OrderDto(String t, double amt, String via) {
	}
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
			String voice,
			boolean viable
	) {
	}

	public record ReadCountsDto(
			int videos,
			int docs,
			int passages
	) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ThemeDto(
			String t,
			String d,
			String level,
			Integer hits
	) {
	}

	public record PocketScoresDto(
			int pain,
			int worse,
			int power,
			int speed
	) {
	}

	public record PocketDto(
			String id,
			String h,
			String ind,
			String niche,
			String know,
			String prob,
			PocketScoresDto scores,
			List<String> src,
			boolean rec,
			boolean grounded
	) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record TypePriceSuggestionDto(
			String price,
			BigDecimal median,
			String pages,
			String rationale,
			String source
	) {
	}

	public record SectionDto(
			String id,
			String title,
			String moment,
			int words,
			String text,
			String status,
			List<String> cites
	) {
	}

	public record BrandPaletteDto(
			String name,
			List<String> cols,
			String display,
			String body
	) {
	}

	public record BrandDto(
			String title,
			int palette,
			BrandPaletteDto paletteDetail,
			int cover,
			List<String> coverImageUrls,
			Map<String, String> pageGraphicUrls
	) {
	}

	public record ProductFileDto(
			String id,
			String fileType,
			int version,
			String downloadPath
	) {
	}

	public record ProductStepDto(
			String status,
			String note,
			List<String> progress,
			Boolean gatePassed,
			String qualityWarning
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

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ChannelStateDto(
			Boolean on,
			Boolean setting,
			Integer week,
			Integer clicks
	) {
	}

	public record PromoVideoDto(
			String title,
			String dur,
			String status
	) {
	}

	public record PromoteStateDto(
			List<PromoVideoDto> videos,
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

