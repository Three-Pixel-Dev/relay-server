package com.expnai.relay.features.distribute.catalog;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Server mirror of the web CHANNELS catalog for Distribute.
 * {@code needsAny}: at least one listed service must be connected (affiliates: Whop now, Gumroad later).
 * {@code needsAll}: every listed service must be connected.
 */
public record DistributeChannelDef(
		String id,
		String name,
		List<String> needsAny,
		List<String> needsAll
) {
	public static final List<DistributeChannelDef> ALL = List.of(
			new DistributeChannelDef("seo", "Marketplace search", List.of(), List.of()),
			new DistributeChannelDef("pinterest", "Pinterest", List.of(), List.of("pinterest")),
			new DistributeChannelDef("leads", "Lead magnet + email list", List.of(), List.of("beehiiv")),
			new DistributeChannelDef("relay", "Relay discovery", List.of(), List.of()),
			new DistributeChannelDef("affiliates", "Affiliates", List.of("whop", "gumroad"), List.of()),
			new DistributeChannelDef("paid", "Small paid test", List.of(), List.of("pinterest")));

	/** Services that are still Coming soon in Connections — cannot satisfy a need yet. */
	public static final List<String> COMING_SOON_SERVICES = List.of(
			"etsy", "gumroad", "payhip", "kdp",
			"youtube", "tiktok", "instagram",
			"convertkit", "stripe",
			"elevenlabs");

	public static Optional<DistributeChannelDef> find(String channelId) {
		if (channelId == null || channelId.isBlank()) {
			return Optional.empty();
		}
		String key = channelId.trim().toLowerCase(Locale.ROOT);
		return ALL.stream().filter(c -> c.id().equals(key)).findFirst();
	}

	public static boolean isComingSoon(String serviceId) {
		if (serviceId == null || serviceId.isBlank()) {
			return false;
		}
		String key = serviceId.trim().toLowerCase(Locale.ROOT);
		return COMING_SOON_SERVICES.stream().anyMatch(s -> s.equals(key));
	}

	public static String serviceDisplayName(String serviceId) {
		if (serviceId == null) {
			return "that service";
		}
		return switch (serviceId.toLowerCase(Locale.ROOT)) {
			case "whop" -> "Whop";
			case "gumroad" -> "Gumroad";
			case "pinterest" -> "Pinterest";
			case "beehiiv" -> "beehiiv";
			case "convertkit" -> "Kit";
			case "youtube" -> "YouTube";
			case "tiktok" -> "TikTok";
			case "instagram" -> "Instagram";
			case "higgsfield" -> "Higgsfield";
			case "elevenlabs" -> "ElevenLabs";
			default -> serviceId;
		};
	}
}
