package com.expnai.relay.features.retrieval;

public record RetrievedPassage(
		Long passageId,
		String text,
		String locator,
		Long sourceId,
		String sourceName,
		String sourceKind,
		double score
) {
}

