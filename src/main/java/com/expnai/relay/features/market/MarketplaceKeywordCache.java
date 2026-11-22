package com.expnai.relay.features.market;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.expnai.relay.features.market.MarketResearchPack.EtsyKeywordResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * 24h Redis cache for marketplace keyword lookups (Etsy Open API / Apify) to avoid
 * redundant scrapes for repeat niches and builds.
 */
@Service
@RequiredArgsConstructor
public class MarketplaceKeywordCache {

	private static final Logger log = LoggerFactory.getLogger(MarketplaceKeywordCache.class);
	private static final String KEY_PREFIX = "relay:market:etsy:";

	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;

	@Value("${relay.market.keyword-cache-ttl-seconds:86400}")
	private long ttlSeconds;

	public Optional<EtsyKeywordResult> get(String keyword) {
		String key = cacheKey(keyword);
		if (key == null) {
			return Optional.empty();
		}
		try {
			String raw = redisTemplate.opsForValue().get(key);
			if (raw == null || raw.isBlank()) {
				return Optional.empty();
			}
			EtsyKeywordResult result = objectMapper.readValue(raw, EtsyKeywordResult.class);
			return Optional.ofNullable(result);
		}
		catch (Exception ex) {
			log.debug("Marketplace keyword cache read failed for '{}': {}", keyword, ex.getMessage());
			return Optional.empty();
		}
	}

	public void put(String keyword, EtsyKeywordResult result) {
		String key = cacheKey(keyword);
		if (key == null || result == null) {
			return;
		}
		try {
			String json = objectMapper.writeValueAsString(result);
			long effectiveTtl = Math.max(60L, ttlSeconds);
			redisTemplate.opsForValue().set(key, json, Duration.ofSeconds(effectiveTtl));
		}
		catch (Exception ex) {
			log.debug("Marketplace keyword cache write failed for '{}': {}", keyword, ex.getMessage());
		}
	}

	private String cacheKey(String keyword) {
		if (keyword == null || keyword.isBlank()) {
			return null;
		}
		String normalized = keyword.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
		return normalized.isEmpty() ? null : KEY_PREFIX + normalized;
	}
}

