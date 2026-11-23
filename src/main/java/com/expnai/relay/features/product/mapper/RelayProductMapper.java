package com.expnai.relay.features.product.mapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.expnai.relay.domain.entity.RelayBrand;
import com.expnai.relay.domain.entity.RelayCitation;
import com.expnai.relay.domain.entity.RelayPocket;
import com.expnai.relay.domain.entity.RelayProduct;
import com.expnai.relay.domain.entity.RelayProductFile;
import com.expnai.relay.domain.entity.RelaySection;
import com.expnai.relay.domain.entity.RelaySource;
import com.expnai.relay.domain.entity.RelaySourceProfile;
import com.expnai.relay.domain.entity.RelayThreadMessage;
import com.expnai.relay.domain.enums.RelaySectionStatus;
import com.expnai.relay.features.ai.AiPricingService;
import com.expnai.relay.features.ai.BrandingLlmService;
import com.expnai.relay.features.product.dto.response.RelayProductResponse;
import com.expnai.relay.features.product.dto.response.RelayProductResponse.BrandDto;
import com.expnai.relay.features.product.dto.response.RelayProductResponse.BrandPaletteDto;
import com.expnai.relay.features.product.dto.response.RelayProductResponse.MetricsDto;
import com.expnai.relay.features.product.dto.response.RelayProductResponse.PocketDto;
import com.expnai.relay.features.product.dto.response.RelayProductResponse.PocketScoresDto;
import com.expnai.relay.features.product.dto.response.RelayProductResponse.ProductFileDto;
import com.expnai.relay.features.product.dto.response.RelayProductResponse.ProductStepDto;
import com.expnai.relay.features.product.dto.response.RelayProductResponse.PromoteStateDto;
import com.expnai.relay.features.product.dto.response.RelayProductResponse.PublishStateDto;
import com.expnai.relay.features.product.dto.response.RelayProductResponse.ReadCountsDto;
import com.expnai.relay.features.product.dto.response.RelayProductResponse.SectionDto;
import com.expnai.relay.features.product.dto.response.RelayProductResponse.SourceItemDto;
import com.expnai.relay.features.product.dto.response.RelayProductResponse.SourceProfileDto;
import com.expnai.relay.features.product.dto.response.RelayProductResponse.ThemeDto;
import com.expnai.relay.features.product.dto.response.RelayProductResponse.ThreadMessageDto;
import com.expnai.relay.features.product.dto.response.RelayProductResponse.TypePriceSuggestionDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RelayProductMapper {

	private static final int TOTAL_BUILD_STEPS = 7;

	private final ObjectMapper objectMapper;
	private final AiPricingService aiPricingService;

	public RelayProductResponse toListDto(
			RelayProduct product,
			RelaySourceProfile profile,
			RelayBrand brand) {
		return toDto(product, List.of(), profile, List.of(), List.of(), List.of(), brand, List.of(), List.of());
	}

	public RelayProductResponse toDto(
			RelayProduct product,
			List<RelaySource> sources,
			RelaySourceProfile profile,
			List<RelayThreadMessage> threadMessages) {
		return toDto(product, sources, profile, List.of(), List.of(), List.of(), threadMessages);
	}

	public RelayProductResponse toDto(
			RelayProduct product,
			List<RelaySource> sources,
			RelaySourceProfile profile,
			List<RelayPocket> pockets,
			List<RelayThreadMessage> threadMessages) {
		return toDto(product, sources, profile, pockets, List.of(), List.of(), threadMessages);
	}

	public RelayProductResponse toDto(
			RelayProduct product,
			List<RelaySource> sources,
			RelaySourceProfile profile,
			List<RelayPocket> pockets,
			List<RelaySection> sections,
			List<RelayThreadMessage> threadMessages) {
		return toDto(product, sources, profile, pockets, sections, List.of(), threadMessages);
	}

	public RelayProductResponse toDto(
			RelayProduct product,
			List<RelaySource> sources,
			RelaySourceProfile profile,
			List<RelayPocket> pockets,
			List<RelaySection> sections,
			List<RelayCitation> citations,
			List<RelayThreadMessage> threadMessages) {
		return toDto(product, sources, profile, pockets, sections, citations, null, threadMessages);
	}

	public RelayProductResponse toDto(
			RelayProduct product,
			List<RelaySource> sources,
			RelaySourceProfile profile,
			List<RelayPocket> pockets,
			List<RelaySection> sections,
			List<RelayCitation> citations,
			RelayBrand brand,
			List<RelayThreadMessage> threadMessages) {
		return toDto(product, sources, profile, pockets, sections, citations, brand, List.of(), threadMessages);
	}

	public RelayProductResponse toDto(
			RelayProduct product,
			List<RelaySource> sources,
			RelaySourceProfile profile,
			List<RelayPocket> pockets,
			List<RelaySection> sections,
			List<RelayCitation> citations,
			RelayBrand brand,
			List<RelayProductFile> files,
			List<RelayThreadMessage> threadMessages) {

		List<SourceItemDto> sourceDtos = sources.stream()
				.map(this::toSourceDto)
				.toList();

		SourceProfileDto profileDto = profile != null ? toProfileDto(profile) : null;

		List<PocketDto> pocketDtos = (pockets != null ? pockets : List.<RelayPocket>of()).stream()
				.map(this::toPocketDto)
				.toList();

		PocketDto chosenPocketDto = null;
		if (product.getChosenPocket() != null) {
			Long chosenId = product.getChosenPocket().getId();
			chosenPocketDto = pocketDtos.stream()
					.filter(p -> p.id().equals(String.valueOf(chosenId)))
					.findFirst()
					.orElseGet(() -> toPocketDto(product.getChosenPocket()));
		}

		Map<Long, List<String>> citesBySectionId = new java.util.HashMap<>();
		if (citations != null) {
			for (RelayCitation c : citations) {
				if (c.getSection() == null || c.getSection().getId() == null) {
					continue;
				}
				String label = c.getLocator();
				if (label == null || label.isBlank()) {
					if (c.getPassage() != null && c.getPassage().getLocator() != null) {
						label = c.getPassage().getLocator();
					}
				}
				if (label != null && !label.isBlank()) {
					citesBySectionId
							.computeIfAbsent(c.getSection().getId(), k -> new ArrayList<>())
							.add(label.trim());
				}
			}
		}

		List<SectionDto> sectionDtos = (sections != null ? sections : List.<RelaySection>of()).stream()
				.map(s -> toSectionDto(s, citesBySectionId.getOrDefault(s.getId(), List.of())))
				.toList();

		List<ProductStepDto> steps = buildSteps(product);

		List<ThreadMessageDto> thread = threadMessages.stream()
				.map(msg -> new ThreadMessageDto(
						msg.getWho() != null ? msg.getWho().getCode().toLowerCase(Locale.ROOT) : "relay",
						msg.getText() != null ? msg.getText() : "",
						(msg.getCreatedAt() != null ? msg.getCreatedAt() : Instant.now()).toString()))
				.toList();

		String mode = product.getMode() != null
				? product.getMode().getCode().toLowerCase(Locale.ROOT)
				: "guided";
		String stage = product.getStage() != null
				? product.getStage().getCode().toLowerCase(Locale.ROOT)
				: "build";
		String type = product.getProductType() != null
				? product.getProductType().getCode().toLowerCase(Locale.ROOT)
				: null;

		List<ProductFileDto> fileDtos = (files != null ? files : List.<RelayProductFile>of()).stream()
				.map(f -> new ProductFileDto(
						String.valueOf(f.getId()),
						f.getFileType() != null ? f.getFileType() : "",
						f.getVersion(),
						"/api/products/" + product.getId() + "/files/" + f.getId() + "/download"))
				.toList();

		Instant createdAt = product.getCreatedAt() != null ? product.getCreatedAt() : Instant.now();
		Instant updatedAt = product.getUpdatedAt() != null ? product.getUpdatedAt() : createdAt;

		int doneSectionCount = (int) (sections != null ? sections : List.<RelaySection>of()).stream()
				.filter(s -> s.getStatus() == RelaySectionStatus.DONE)
				.count();
		boolean pricingAvailable = aiPricingService.isPricingAvailable();
		String pricingMessage = pricingAvailable ? null : AiPricingService.PRICING_UNAVAILABLE_MESSAGE;
		BigDecimal remainingEstimate = aiPricingService
				.estimateRemainingUsd(product, sourceDtos.size(), sectionDtos.size(), doneSectionCount)
				.orElse(null);

		Map<String, TypePriceSuggestionDto> typePriceSuggestions = parseTypePriceSuggestions(
				product.getTypePriceSuggestionsJson());

		return new RelayProductResponse(
				String.valueOf(product.getId()),
				product.getName() != null ? product.getName() : "",
				product.getInput() != null ? product.getInput() : "",
				mode,
				product.isForElse(),
				product.getClientName() != null ? product.getClientName() : "",
				product.getPartner() != null ? String.valueOf(product.getPartner().getId()) : null,
				sourceDtos,
				profileDto,
				List.of(),
				pocketDtos,
				chosenPocketDto,
				type,
				typePriceSuggestions,
				sectionDtos,
				product.getWordCount() != null ? product.getWordCount() : 0,
				brand != null ? toBrandDto(product, brand) : null,
				fileDtos,
				stage,
				product.getBuildStep(),
				steps,
				thread,
				new PublishStateDto(List.of(), Map.of(), Map.of(), null, "todo", null),
				Map.of(),
				new PromoteStateDto(List.of(), "todo", false),
				new MetricsDto(0, 0.0, 0, 0),
				product.getSpend() != null ? product.getSpend() : BigDecimal.ZERO,
				remainingEstimate,
				pricingAvailable,
				pricingMessage,
				product.getQualityWarning(),
				product.isCancelled(),
				createdAt.toString(),
				updatedAt.toString());
	}

	private SourceItemDto toSourceDto(RelaySource source) {
		String kind = source.getKind() != null
				? source.getKind().getCode().toLowerCase(Locale.ROOT)
				: "file";
		String status = source.getStatus() != null
				? source.getStatus().getCode().toLowerCase(Locale.ROOT)
				: "pending";
		return new SourceItemDto(
				String.valueOf(source.getId()),
				kind,
				source.getName() != null ? source.getName() : "",
				source.getUrl(),
				source.getFileSize(),
				status,
				source.getErrorMessage());
	}

	private SourceProfileDto toProfileDto(RelaySourceProfile profile) {
		List<ThemeDto> themes = List.of();
		if (profile.getThemesJson() != null && !profile.getThemesJson().isBlank()) {
			try {
				themes = objectMapper.readValue(profile.getThemesJson(), new TypeReference<List<ThemeDto>>() {
				});
			}
			catch (Exception ignored) {
				themes = List.of();
			}
		}
		ReadCountsDto read = new ReadCountsDto(
				profile.getVideosCount() != null ? profile.getVideosCount() : 0,
				profile.getDocsCount() != null ? profile.getDocsCount() : 0,
				profile.getPassagesCount() != null ? profile.getPassagesCount() : 0);

		return new SourceProfileDto(
				read,
				profile.getNiche() != null ? profile.getNiche() : "",
				profile.getTransformation() != null ? profile.getTransformation() : "",
				themes,
				profile.getVerdict() != null ? profile.getVerdict() : "",
				profile.getAudience() != null ? profile.getAudience() : "",
				profile.getVoice() != null ? profile.getVoice() : "",
				profile.isViable());
	}

	private PocketDto toPocketDto(RelayPocket pocket) {
		PocketScoresDto scores = new PocketScoresDto(75, 72, 74, 78);
		if (pocket.getScoresJson() != null && !pocket.getScoresJson().isBlank()) {
			try {
				scores = objectMapper.readValue(pocket.getScoresJson(), PocketScoresDto.class);
			}
			catch (Exception ignored) {
			}
		}
		List<String> evidence = List.of();
		if (pocket.getEvidenceJson() != null && !pocket.getEvidenceJson().isBlank()) {
			try {
				evidence = objectMapper.readValue(pocket.getEvidenceJson(), new TypeReference<List<String>>() {
				});
			}
			catch (Exception ignored) {
				evidence = List.of();
			}
		}
		return new PocketDto(
				String.valueOf(pocket.getId()),
				pocket.getHeadline() != null ? pocket.getHeadline() : "",
				pocket.getIndustry() != null ? pocket.getIndustry() : "",
				pocket.getNiche() != null ? pocket.getNiche() : "",
				pocket.getKnowledge() != null ? pocket.getKnowledge() : "",
				pocket.getProblem() != null ? pocket.getProblem() : "",
				scores,
				evidence != null ? evidence : List.of(),
				pocket.isRecommended(),
				pocket.isGrounded());
	}

	private SectionDto toSectionDto(RelaySection section, List<String> cites) {
		String status = section.getStatus() != null
				? section.getStatus().getCode().toLowerCase(Locale.ROOT)
				: "todo";
		return new SectionDto(
				String.valueOf(section.getId()),
				section.getTitle() != null ? section.getTitle() : "",
				section.getMoment() != null ? section.getMoment() : "",
				section.getWords() != null ? section.getWords() : 0,
				section.getText() != null ? section.getText() : "",
				status,
				cites != null ? List.copyOf(cites) : List.of());
	}

	private BrandDto toBrandDto(RelayProduct product, RelayBrand brand) {
		int paletteIdx = brand.getPaletteIndex() != null ? brand.getPaletteIndex() : 0;
		BrandPaletteDto paletteDetail = resolveBrandPalette(brand);
		int cover = brand.getCoverVariant() != null
				? Math.max(0, Math.min(2, brand.getCoverVariant()))
				: 0;
		List<String> coverUrls = new ArrayList<>();
		if (brand.getCoverImageKeysJson() != null && !brand.getCoverImageKeysJson().isBlank()) {
			try {
				List<String> keys = objectMapper.readValue(
						brand.getCoverImageKeysJson(), new TypeReference<List<String>>() {
						});
				for (int i = 0; i < keys.size(); i++) {
					String key = keys.get(i);
					coverUrls.add((key != null && !key.isBlank())
							? "/api/products/" + product.getId() + "/brand/covers/" + i
							: null);
				}
			}
			catch (Exception ignored) {
			}
		}

		Map<String, String> pageGraphicUrls = new java.util.LinkedHashMap<>();
		if (brand.getPageGraphicsJson() != null && !brand.getPageGraphicsJson().isBlank()) {
			try {
				Map<String, String> keys = objectMapper.readValue(
						brand.getPageGraphicsJson(), new TypeReference<Map<String, String>>() {
						});
				if (keys != null) {
					for (Map.Entry<String, String> entry : keys.entrySet()) {
						if (entry.getValue() != null && !entry.getValue().isBlank()) {
							pageGraphicUrls.put(
									entry.getKey(),
									"/api/products/" + product.getId() + "/brand/pages/" + entry.getKey());
						}
					}
				}
			}
			catch (Exception ignored) {
			}
		}

		return new BrandDto(
				brand.getTitle() != null ? brand.getTitle() : "",
				paletteIdx,
				paletteDetail,
				cover,
				coverUrls,
				pageGraphicUrls);
	}

	private BrandPaletteDto resolveBrandPalette(RelayBrand brand) {
		if (brand.getPaletteJson() != null && !brand.getPaletteJson().isBlank()) {
			try {
				BrandingLlmService.BrandPalette custom = objectMapper.readValue(
						brand.getPaletteJson(), BrandingLlmService.BrandPalette.class);
				if (custom != null && custom.cols() != null && custom.cols().size() >= 5) {
					return new BrandPaletteDto(
							custom.name() != null && !custom.name().isBlank() ? custom.name() : "Custom",
							custom.cols().subList(0, 5),
							custom.display() != null && !custom.display().isBlank() ? custom.display() : "Merriweather",
							custom.body() != null && !custom.body().isBlank() ? custom.body() : "Open Sans");
				}
			}
			catch (Exception ignored) {
			}
		}
		BrandingLlmService.BrandPalette house = BrandingLlmService.housePalette(brand.getPaletteIndex());
		return new BrandPaletteDto(house.name(), house.cols(), house.display(), house.body());
	}

	private List<ProductStepDto> buildSteps(RelayProduct product) {
		int cur = product.getBuildStep();
		String currentStatus = (product.getStepStatus() != null && !product.getStepStatus().isBlank())
				? product.getStepStatus()
				: "todo";
		String currentNote = product.getStepNote() != null ? product.getStepNote() : "";
		List<String> currentProgress = parseProgress(product.getStepProgress());

		List<ProductStepDto> steps = new ArrayList<>(TOTAL_BUILD_STEPS);
		for (int i = 0; i < TOTAL_BUILD_STEPS; i++) {
			if (i < cur) {
				steps.add(new ProductStepDto("done", i == 0 ? currentNote : "", null, null, null));
			}
			else if (i == cur) {
				Boolean gatePassed = ("quality_gate".equals(currentStatus) || "gate".equals(currentStatus))
						? Boolean.FALSE
						: (i == 4 && product.isVoiceApproved() ? Boolean.TRUE : null);
				steps.add(new ProductStepDto(
						currentStatus,
						currentNote,
						currentProgress,
						gatePassed,
						product.getQualityWarning()));
			}
			else {
				steps.add(new ProductStepDto("todo", "", null, null, null));
			}
		}
		return steps;
	}

	private Map<String, TypePriceSuggestionDto> parseTypePriceSuggestions(String json) {
		if (json == null || json.isBlank()) {
			return Map.of();
		}
		try {
			Map<String, TypePriceSuggestionDto> parsed = objectMapper.readValue(
					json, new TypeReference<Map<String, TypePriceSuggestionDto>>() {
					});
			return parsed != null ? parsed : Map.of();
		}
		catch (Exception ignored) {
			return Map.of();
		}
	}

	private List<String> parseProgress(String progressJson) {
		if (progressJson == null || progressJson.isBlank()) {
			return null;
		}
		try {
			List<String> list = objectMapper.readValue(progressJson, new TypeReference<List<String>>() {
			});
			return (list == null || list.isEmpty()) ? null : list;
		}
		catch (Exception ignored) {
			return null;
		}
	}
}

