package com.expnai.relay.features.product.ingest;

import java.util.List;

import org.springframework.stereotype.Service;

import com.expnai.relay.domain.entity.RelayPassage;
import com.expnai.relay.domain.entity.RelayProduct;
import com.expnai.relay.domain.entity.RelaySource;
import com.expnai.relay.features.ai.ContentTeachabilityClassifier;
import com.expnai.relay.features.ai.ContentTeachabilityClassifier.ClassificationResult;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SourceQualityService {

	public static final int MIN_PASSAGES_FOR_QUALITY = 20;
	public static final String CLIENT_QUALITY_WARNING =
			"This source is too thin to build a product from. Add more sources or tell us what you know.";
	public static final String NON_TEACHABLE_WARNING_PREFIX =
			"These sources cannot support a paid digital product.";

	private final ContentTeachabilityClassifier contentTeachabilityClassifier;

	public record QualityCheckResult(
			boolean thin,
			boolean nonTeachable,
			List<String> reasons,
			String warningMessage
	) {
		public boolean failsGate() {
			return thin || nonTeachable;
		}
	}

	public QualityCheckResult evaluate(
			String chatApiKey,
			RelayProduct product,
			List<RelaySource> sources,
			List<RelayPassage> passages) {
		int passagesCount = passages != null ? passages.size() : 0;
		boolean thin = passagesCount < MIN_PASSAGES_FOR_QUALITY;

		// Locked: skip LLM classifier when thin to save cost; reason = passage count
		if (thin) {
			String reason = "Only " + passagesCount + " passage" + (passagesCount == 1 ? "" : "s")
					+ " indexed (minimum 20 recommended).";
			String warningMessage = CLIENT_QUALITY_WARNING + " (" + reason + ")";
			return new QualityCheckResult(true, false, List.of(reason), warningMessage);
		}

		ClassificationResult classified = contentTeachabilityClassifier.classify(
				chatApiKey,
				product,
				sources,
				passages);

		if (classified.nonTeachable()) {
			String reason = (classified.reason() != null && !classified.reason().isBlank())
					? classified.reason()
					: "Sources were classified as non-teachable (" + classified.contentType() + ").";
			String warningMessage = NON_TEACHABLE_WARNING_PREFIX + " " + reason + " Add teachable sources to continue.";
			return new QualityCheckResult(false, true, List.of(reason), warningMessage);
		}

		return new QualityCheckResult(false, false, List.of(), null);
	}
}
