package com.expnai.relay.features.partners.scout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.expnai.relay.features.partners.dto.response.RelayScoutResultResponse;

/**
 * Deterministic creator pool matching Relay-demo Partners scout (handoff data.js).
 * Real YouTube/Instagram discovery can replace this later.
 */
@Component
public class RelayScoutCatalog {

	private static final List<ScoutSeed> SCOUT_POOL = List.of(
			seed("Hana Whitfield", "@slowmotherhood.hana", "instagram", "76k",
					"Low-maintenance motherhood", "Bio: “Join 6,000 moms on my Sunday letter”",
					"6,000", "No paid product found", "hello@slowmotherhood.example"),
			seed("Tom Arkwright", "@reactivedogdad", "youtube", "41k",
					"Reactive dog training", "About: “Free weekly training email ↓” (beehiiv link)",
					"not stated", "No paid product found", "tom@reactivedogdad.example"),
			seed("Priya Nandakumar", "@onepaycheckhome", "instagram", "128k",
					"Single-income budgeting", "Link in bio → Kit signup form, “11k subscribers”",
					"11,000", "Sells one $5 printable", "priya@onepaycheckhome.example"),
			seed("Marco Estévez", "@studywithmarco", "youtube", "212k",
					"Exam technique for college students",
					"Pinned comment: “Join the newsletter for my recall sheets”",
					"not stated", "No paid product found", "team@studywithmarco.example"),
			seed("Greta Lindqvist", "@adhd.at.work", "instagram", "54k",
					"ADHD routines for adults", "Bio: “Free Friday email, 3 ideas”",
					"not stated", "No paid product found", "greta@adhdatwork.example"),
			seed("Devon Brooks", "@firstgardenyear", "youtube", "33k",
					"Beginner vegetable gardening", "About: Substack link, “4,200 readers”",
					"4,200", "No paid product found", "devon@firstgardenyear.example"),
			seed("Aiko Tanabe", "@calmclassroom.aiko", "instagram", "89k",
					"Classroom management for new teachers",
					"Story highlight “Newsletter”, link to beehiiv",
					"not stated", "Sells lesson plans on TPT", "aiko@calmclassroom.example"),
			seed("Luis Carvalho", "@weddingonabudget.luis", "youtube", "67k",
					"Budget wedding planning", "Description: “Get my free checklist by email”",
					"not stated", "No paid product found", "luis@weddingonabudget.example"));

	private static final List<String> SCOUT_NAMES = List.of(
			"Maya Okafor", "Jonas Petrov", "Leila Haddad", "Ravi Menon", "Sofia Marino",
			"Caleb Wright", "Nina Kowalski", "Diego Fuentes", "Amara Singh", "Owen Fischer",
			"Yuki Morita", "Elena Popescu");

	private static final List<String> SCOUT_SIGNALS = List.of(
			"Bio: “Free weekly email, link below”",
			"About: newsletter link (beehiiv)",
			"Link in bio → Kit signup form",
			"Pinned comment: “Join my email list for the templates”",
			"Description: “Get my free checklist by email”",
			"Story highlight “Newsletter”, Substack link");

	public List<RelayScoutResultResponse> scout(String query, List<String> platforms, List<String> skipHandles) {
		List<String> plats = (platforms == null || platforms.isEmpty())
				? List.of("youtube", "instagram")
				: platforms.stream().map(p -> p.toLowerCase(Locale.ROOT)).toList();
		List<String> skip = skipHandles == null ? List.of() : skipHandles;

		String q = query == null ? "" : query.trim();
		List<String> tokens = q.isEmpty()
				? List.of()
				: List.of(q.toLowerCase(Locale.ROOT).split("\\s+")).stream()
						.filter(w -> w.length() > 2)
						.toList();

		List<RelayScoutResultResponse> out = new ArrayList<>();
		for (ScoutSeed c : SCOUT_POOL) {
			if (!plats.contains(c.plat()) || skip.contains(c.handle())) {
				continue;
			}
			if (!tokens.isEmpty()) {
				String hay = (c.niche() + " " + c.handle()).toLowerCase(Locale.ROOT);
				boolean hit = tokens.stream().anyMatch(hay::contains);
				if (!hit) {
					continue;
				}
			}
			out.add(toResponse(c));
		}

		String rawSlug = q.isEmpty() ? "creator" : q.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
		String slug = rawSlug.isEmpty() ? "creator" : rawSlug.substring(0, Math.min(14, rawSlug.length()));
		String niche = q.isEmpty() ? "General how-to" : Character.toUpperCase(q.charAt(0)) + q.substring(1);
		int seed = slug.chars().sum() + skip.size();

		for (int k = 0; out.size() < 6 && k < 24; k++) {
			String name = SCOUT_NAMES.get((seed + k) % SCOUT_NAMES.size());
			String first = name.split(" ")[0].toLowerCase(Locale.ROOT);
			String plat = plats.get((seed + k) % plats.size());
			String[] handleOpts = {
					"@" + slug + "." + first,
					"@" + first + "." + slug,
					"@" + slug + "with" + first
			};
			String handle = handleOpts[k % 3];
			if (skip.contains(handle) || out.stream().anyMatch(c -> handle.equals(c.handle()))) {
				continue;
			}
			int f = 18 + ((seed * 7 + k * 23) % 190);
			boolean listed = k % 3 == 0;
			String list = listed ? String.format(Locale.US, "%,d", (2 + (seed + k) % 9) * 1000) : "not stated";
			String product = k % 4 == 3 ? "Sells one low-priced printable" : "No paid product found";
			String url = plat.equals("youtube")
					? "youtube.com/" + handle
					: "instagram.com/" + handle.replace("@", "");
			out.add(new RelayScoutResultResponse(
					name,
					handle,
					plat,
					url,
					f + "k",
					niche,
					SCOUT_SIGNALS.get((seed + k) % SCOUT_SIGNALS.size()),
					list,
					product,
					first + "@" + slug + first + ".example"));
		}

		if (q.isEmpty()) {
			for (ScoutSeed c : SCOUT_POOL) {
				if (out.size() >= 6) {
					break;
				}
				if (!plats.contains(c.plat()) || skip.contains(c.handle())) {
					continue;
				}
				boolean already = out.stream().anyMatch(r -> c.handle().equals(r.handle()));
				if (!already) {
					out.add(0, toResponse(c));
				}
			}
		}

		return out.stream().limit(6).collect(Collectors.toList());
	}

	private static RelayScoutResultResponse toResponse(ScoutSeed c) {
		String url = c.plat().equals("youtube")
				? "youtube.com/" + c.handle()
				: "instagram.com/" + c.handle().replace("@", "");
		return new RelayScoutResultResponse(
				c.name(), c.handle(), c.plat(), url, c.followers(), c.niche(),
				c.signal(), c.list(), c.product(), c.email());
	}

	private static ScoutSeed seed(
			String name, String handle, String plat, String followers,
			String niche, String signal, String list, String product, String email) {
		return new ScoutSeed(name, handle, plat, followers, niche, signal, list, product, email);
	}

	private record ScoutSeed(
			String name,
			String handle,
			String plat,
			String followers,
			String niche,
			String signal,
			String list,
			String product,
			String email
	) {
	}
}
