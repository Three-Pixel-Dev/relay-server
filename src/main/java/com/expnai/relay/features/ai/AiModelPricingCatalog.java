package com.expnai.relay.features.ai;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * Fetches live AI model token pricing from {@code models.dev/api.json} and caches a compact
 * {@code provider/model -> {inputPerMtok, outputPerMtok}} map in Redis with a 6h soft TTL and
 * stale-while-revalidate fallback. No hardcoded dollar fallbacks.
 */
@Service
@RequiredArgsConstructor
public class AiModelPricingCatalog {

	private static final Logger log = LoggerFactory.getLogger(AiModelPricingCatalog.class);
	private static final String REDIS_KEY = "relay:ai:pricing:catalog:v1";
	private static final List<String> TRACKED_PROVIDERS = List.of("google", "openai");
	private static final long FAILURE_RETRY_COOLDOWN_SECONDS = 60L;

	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;

	@Value("${relay.ai.pricing.catalog-url:https://models.dev/api.json}")
	private String catalogUrl;

	@Value("${relay.ai.pricing.cache-ttl-seconds:21600}")
	private long cacheTtlSeconds;

	private final Object refreshLock = new Object();
	private final AtomicLong lastFailedFetchEpochSec = new AtomicLong(0L);

	public record ModelRates(BigDecimal inputPerMtok, BigDecimal outputPerMtok) {
	}

	private record CachedCatalog(long fetchedAtEpochSec, Map<String, ModelRates> rates) {
	}

	@EventListener(ApplicationReadyEvent.class)
	public void warmOnStartup() {
		Thread warmer = new Thread(() -> {
			try {
				ensureCatalogLoaded();
			}
			catch (Exception ex) {
				log.warn("Best-effort AI pricing catalog warm-up failed: {}", ex.getMessage());
			}
		}, "relay-pricing-catalog-warm");
		warmer.setDaemon(true);
		warmer.start();
	}

	public Optional<ModelRates> getRates(String provider, String model) {
		if (model == null || model.isBlank()) {
			return Optional.empty();
		}
		CachedCatalog catalog = ensureCatalogLoaded();
		if (catalog == null || catalog.rates().isEmpty()) {
			return Optional.empty();
		}

		String normalizedProvider = normalizeProvider(provider);
		String normalizedModel = normalizeModelId(model);
		String key = normalizedProvider + "/" + normalizedModel;

		ModelRates exact = catalog.rates().get(key);
		if (exact != null) {
			return Optional.of(exact);
		}
		// Handle minor preview suffixes (e.g. gemini-embedding-2-preview -> gemini-embedding-2)
		if (normalizedModel.endsWith("-preview")) {
			String baseKey = normalizedProvider + "/" + normalizedModel.substring(0, normalizedModel.length() - "-preview".length());
			ModelRates base = catalog.rates().get(baseKey);
			if (base != null) {
				return Optional.of(base);
			}
		}
		return Optional.empty();
	}

	private CachedCatalog ensureCatalogLoaded() {
		long now = Instant.now().getEpochSecond();
		CachedCatalog cached = readFromRedis();
		if (cached != null && !isExpired(cached, now)) {
			return cached;
		}

		// If Redis had no entry and we just failed recently, avoid blocking every request
		if (cached == null && (now - lastFailedFetchEpochSec.get()) < FAILURE_RETRY_COOLDOWN_SECONDS) {
			return null;
		}

		synchronized (refreshLock) {
			now = Instant.now().getEpochSecond();
			cached = readFromRedis();
			if (cached != null && !isExpired(cached, now)) {
				return cached;
			}
			if (cached == null && (now - lastFailedFetchEpochSec.get()) < FAILURE_RETRY_COOLDOWN_SECONDS) {
				return null;
			}

			try {
				CachedCatalog fresh = fetchRemoteCatalog(now);
				writeToRedis(fresh);
				lastFailedFetchEpochSec.set(0L);
				return fresh;
			}
			catch (Exception ex) {
				lastFailedFetchEpochSec.set(now);
				if (cached != null) {
					log.warn("AI pricing catalog refresh failed ({}); reusing stale Redis cache from {}",
							ex.getMessage(), Instant.ofEpochSecond(cached.fetchedAtEpochSec()));
					return cached;
				}
				log.warn("AI pricing catalog unavailable (fetch failed and Redis has no cached catalog): {}", ex.getMessage());
				return null;
			}
		}
	}

	private boolean isExpired(CachedCatalog cached, long nowEpochSec) {
		long ttl = Math.max(60L, cacheTtlSeconds);
		return (nowEpochSec - cached.fetchedAtEpochSec()) >= ttl;
	}

	private CachedCatalog fetchRemoteCatalog(long nowEpochSec) throws Exception {
		HttpClient http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(8))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(catalogUrl.trim()))
				.timeout(Duration.ofSeconds(15))
				.header("Accept", "application/json")
				.GET()
				.build();

		HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
			throw new IllegalStateException("models.dev returned HTTP " + resp.statusCode());
		}

		JsonNode root = objectMapper.readTree(resp.body());
		Map<String, ModelRates> rates = new LinkedHashMap<>();

		for (String providerKey : TRACKED_PROVIDERS) {
			JsonNode modelsNode = root.path(providerKey).path("models");
			if (!modelsNode.isObject()) {
				continue;
			}
			Iterator<Map.Entry<String, JsonNode>> fields = modelsNode.fields();
			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> entry = fields.next();
				String modelId = normalizeModelId(entry.getKey());
				JsonNode costNode = entry.getValue().path("cost");
				if (!costNode.isObject() || !costNode.has("input")) {
					continue;
				}
				BigDecimal input = parseDecimal(costNode.path("input"));
				if (input == null) {
					continue;
				}
				BigDecimal output = parseDecimal(costNode.path("output"));
				if (output == null) {
					output = BigDecimal.ZERO;
				}
				rates.put(providerKey + "/" + modelId, new ModelRates(input, output));
			}
		}

		if (rates.isEmpty()) {
			throw new IllegalStateException("models.dev payload contained no google/openai model prices");
		}
		log.info("Loaded {} live AI model rates from {}", rates.size(), catalogUrl);
		return new CachedCatalog(nowEpochSec, Map.copyOf(rates));
	}

	private CachedCatalog readFromRedis() {
		try {
			String raw = redisTemplate.opsForValue().get(REDIS_KEY);
			if (raw == null || raw.isBlank()) {
				return null;
			}
			JsonNode root = objectMapper.readTree(raw);
			long fetchedAt = root.path("fetchedAtEpochSec").asLong(0L);
			JsonNode ratesNode = root.path("rates");
			if (!ratesNode.isObject()) {
				return null;
			}
			Map<String, ModelRates> rates = new LinkedHashMap<>();
			Iterator<Map.Entry<String, JsonNode>> fields = ratesNode.fields();
			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> entry = fields.next();
				BigDecimal in = parseDecimal(entry.getValue().path("inputPerMtok"));
				BigDecimal out = parseDecimal(entry.getValue().path("outputPerMtok"));
				if (in != null && out != null) {
					rates.put(entry.getKey(), new ModelRates(in, out));
				}
			}
			if (rates.isEmpty()) {
				return null;
			}
			return new CachedCatalog(fetchedAt, Map.copyOf(rates));
		}
		catch (Exception ex) {
			log.warn("Could not read AI pricing catalog from Redis: {}", ex.getMessage());
			return null;
		}
	}

	private void writeToRedis(CachedCatalog catalog) {
		try {
			String json = objectMapper.writeValueAsString(catalog);
			// Store without hard Redis expiry so stale-while-revalidate works if models.dev is temporarily unreachable
			redisTemplate.opsForValue().set(REDIS_KEY, json);
		}
		catch (Exception ex) {
			log.warn("Could not write AI pricing catalog to Redis: {}", ex.getMessage());
		}
	}

	private BigDecimal parseDecimal(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return null;
		}
		if (node.isNumber()) {
			return node.decimalValue();
		}
		if (node.isTextual()) {
			try {
				return new BigDecimal(node.asText().trim());
			}
			catch (NumberFormatException ignored) {
			}
		}
		return null;
	}

	private String normalizeProvider(String provider) {
		if (provider == null || provider.isBlank()) {
			return "google";
		}
		String p = provider.trim().toLowerCase(Locale.ROOT);
		if ("gemini".equals(p) || "google".equals(p)) {
			return "google";
		}
		if ("openai".equals(p)) {
			return "openai";
		}
		return p;
	}

	private String normalizeModelId(String model) {
		String m = model.trim().toLowerCase(Locale.ROOT);
		if (m.startsWith("models/")) {
			m = m.substring("models/".length());
		}
		return m;
	}
}

