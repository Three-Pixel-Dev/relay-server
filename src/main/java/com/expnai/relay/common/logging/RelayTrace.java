package com.expnai.relay.common.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dedicated pipeline trace loggers under {@code com.expnai.relay.trace.*}.
 * <p>
 * Controlled by {@code logging.level.com.expnai.relay.trace=${RELAY_TRACE_LEVEL:INFO}}:
 * stays completely silent at default {@code INFO} in production, and emits structured
 * {@code [scrape]}, {@code [extract]}, {@code [ai]}, {@code [estimate]}, and {@code [step]}
 * lines when set to {@code DEBUG} for demos and local debugging.
 * <p>
 * Never log API keys, credentials, or full raw document/prompt bodies through this helper.
 */
public final class RelayTrace {

	private static final Logger SCRAPE = LoggerFactory.getLogger("com.expnai.relay.trace.scrape");
	private static final Logger EXTRACT = LoggerFactory.getLogger("com.expnai.relay.trace.extract");
	private static final Logger AI = LoggerFactory.getLogger("com.expnai.relay.trace.ai");
	private static final Logger ESTIMATE = LoggerFactory.getLogger("com.expnai.relay.trace.estimate");
	private static final Logger STEP = LoggerFactory.getLogger("com.expnai.relay.trace.step");

	private RelayTrace() {
	}

	public static boolean isScrapeEnabled() {
		return SCRAPE.isDebugEnabled();
	}

	public static boolean isExtractEnabled() {
		return EXTRACT.isDebugEnabled();
	}

	public static boolean isAiEnabled() {
		return AI.isDebugEnabled();
	}

	public static boolean isEstimateEnabled() {
		return ESTIMATE.isDebugEnabled();
	}

	public static boolean isStepEnabled() {
		return STEP.isDebugEnabled();
	}

	public static void scrape(String format, Object... args) {
		if (SCRAPE.isDebugEnabled()) {
			SCRAPE.debug("[scrape] " + format, args);
		}
	}

	public static void extract(String format, Object... args) {
		if (EXTRACT.isDebugEnabled()) {
			EXTRACT.debug("[extract] " + format, args);
		}
	}

	public static void ai(String format, Object... args) {
		if (AI.isDebugEnabled()) {
			AI.debug("[ai] " + format, args);
		}
	}

	public static void estimate(String format, Object... args) {
		if (ESTIMATE.isDebugEnabled()) {
			ESTIMATE.debug("[estimate] " + format, args);
		}
	}

	public static void step(String format, Object... args) {
		if (STEP.isDebugEnabled()) {
			STEP.debug("[step] " + format, args);
		}
	}

	public static String truncate(String text, int maxChars) {
		if (text == null || text.isBlank()) {
			return "";
		}
		String collapsed = text.replaceAll("\\s+", " ").trim();
		int limit = Math.max(16, maxChars);
		return collapsed.length() <= limit ? collapsed : collapsed.substring(0, limit) + "…";
	}
}

