package com.expnai.relay.features.gmail.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.expnai.relay.features.gmail.config.GmailProperties;

class GoogleGmailApiClientRetryTest {

	private final List<Long> sleeps = new ArrayList<>();

	private GoogleGmailApiClient client() {
		GmailProperties props = new GmailProperties();
		props.setRateLimitInitialBackoffMs(100);
		props.setRateLimitMaxBackoffMs(300);
		props.setRateLimitMaxAttempts(4);
		return new GoogleGmailApiClient(props) {
			@Override
			void sleep(long ms) {
				sleeps.add(ms);
			}
		};
	}

	private static GmailApiException rateLimited() {
		return new GmailApiException("429", 429, false, true, false, null);
	}

	@Test
	void retriesWithExponentialBackoffThenSucceeds() {
		AtomicInteger calls = new AtomicInteger();
		String result = client().withRetry(() -> {
			if (calls.incrementAndGet() < 4) {
				throw rateLimited();
			}
			return "ok";
		});
		assertEquals("ok", result);
		assertEquals(List.of(100L, 200L, 300L), sleeps);
	}

	@Test
	void givesUpAfterMaxAttempts() {
		AtomicInteger calls = new AtomicInteger();
		assertThrows(GmailApiException.class, () -> client().withRetry(() -> {
			calls.incrementAndGet();
			throw rateLimited();
		}));
		assertEquals(4, calls.get());
	}

	@Test
	void doesNotRetryNonRateLimitErrors() {
		AtomicInteger calls = new AtomicInteger();
		assertThrows(GmailApiException.class, () -> client().withRetry(() -> {
			calls.incrementAndGet();
			throw new GmailApiException("auth", 401, false, false, true, null);
		}));
		assertEquals(1, calls.get());
		assertEquals(List.of(), sleeps);
	}
}
