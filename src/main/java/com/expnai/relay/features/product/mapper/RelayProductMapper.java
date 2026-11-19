package com.expnai.relay.features.product.mapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.expnai.relay.domain.entity.RelayProduct;
import com.expnai.relay.domain.entity.RelaySource;
import com.expnai.relay.domain.entity.RelaySourceProfile;
import com.expnai.relay.domain.entity.RelayThreadMessage;
import com.expnai.relay.features.product.dto.response.RelayProductResponse;
import com.expnai.relay.features.product.dto.response.RelayProductResponse.MetricsDto;
import com.expnai.relay.features.product.dto.response.RelayProductResponse.ProductStepDto;
import com.expnai.relay.features.product.dto.response.RelayProductResponse.PromoteStateDto;
import com.expnai.relay.features.product.dto.response.RelayProductResponse.PublishStateDto;
import com.expnai.relay.features.product.dto.response.RelayProductResponse.ReadCountsDto;
import com.expnai.relay.features.product.dto.response.RelayProductResponse.SourceItemDto;
import com.expnai.relay.features.product.dto.response.RelayProductResponse.SourceProfileDto;
import com.expnai.relay.features.product.dto.response.RelayProductResponse.ThemeDto;
import com.expnai.relay.features.product.dto.response.RelayProductResponse.ThreadMessageDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RelayProductMapper {

	private static final int TOTAL_BUILD_STEPS = 7;

	private final ObjectMapper objectMapper;

	public RelayProductResponse toDto(
			RelayProduct product,
			List<RelaySource> sources,
			RelaySourceProfile profile,
			List<RelayThreadMessage> threadMessages) {

		List<SourceItemDto> sourceDtos = sources.stream()
				.map(this::toSourceDto)
				.toList();

		SourceProfileDto profileDto = profile != null ? toProfileDto(profile) : null;

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

		Instant createdAt = product.getCreatedAt() != null ? product.getCreatedAt() : Instant.now();
		Instant updatedAt = product.getUpdatedAt() != null ? product.getUpdatedAt() : createdAt;

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
				List.of(),
				null,
				type,
				List.of(),
				product.getWordCount() != null ? product.getWordCount() : 0,
				null,
				stage,
				product.getBuildStep(),
				steps,
				thread,
				new PublishStateDto(List.of(), Map.of(), Map.of(), null, "todo", null),
				Map.of(),
				new PromoteStateDto(List.of(), "todo", false),
				new MetricsDto(0, 0.0, 0, 0),
				product.getSpend() != null ? product.getSpend() : BigDecimal.ZERO,
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
				profile.getVoice() != null ? profile.getVoice() : "");
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
				Boolean gatePassed = "quality_gate".equals(currentStatus) ? Boolean.FALSE : null;
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

