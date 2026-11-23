package com.expnai.relay.features.whop.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.expnai.relay.common.exception.BadGatewayException;
import com.expnai.relay.common.exception.BusinessRuleException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

class WhopApiClientTest {

	private record Seen(String method, String path, String auth, String version, String body) {
	}

	private final ObjectMapper mapper = new ObjectMapper();
	private final List<Seen> seen = new CopyOnWriteArrayList<>();
	private HttpServer server;
	private WhopApiClient client;
	private volatile int status = 200;
	private volatile String response = "{}";

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			seen.add(new Seen(
					exchange.getRequestMethod(),
					exchange.getRequestURI().getPath(),
					exchange.getRequestHeaders().getFirst("Authorization"),
					exchange.getRequestHeaders().getFirst("Api-Version-Date"),
					body));
			byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
			if (bytes.length > 0) {
				exchange.getResponseBody().write(bytes);
			}
			exchange.close();
		});
		server.start();
		client = new WhopApiClient(mapper, "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/", "2026-11-20", 10);
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	@Test
	void getsAccountWithBearerKeyAndVersionHeader() {
		response = "{\"id\":\"biz_123\",\"title\":\"Acme\"}";
		WhopApiClient.WhopAccount account = client.getCurrentAccount("  key-123456  ");
		assertEquals("biz_123", account.id());
		assertEquals("Acme", account.title());
		Seen call = seen.get(0);
		assertEquals("GET", call.method());
		assertEquals("/api/v1/accounts/me", call.path());
		assertEquals("Bearer key-123456", call.auth());
		assertEquals("2026-11-20", call.version());
	}

	@Test
	void createsProductWithRedirectAndAffiliate() throws Exception {
		response = "{\"id\":\"prod_1\",\"route\":\"acme\"}";
		WhopApiClient.WhopProduct product = client.createProduct(
				"key-123456", "Wi-Fi Blueprint", "Fast", "https://api.example.com/api/public/deliveries/tok",
				new BigDecimal("20"));
		assertEquals("prod_1", product.id());
		JsonNode body = mapper.readTree(seen.get(0).body());
		assertEquals("/api/v1/products", seen.get(0).path());
		assertEquals("Wi-Fi Blueprint", body.get("title").asText());
		assertEquals("visible", body.get("visibility").asText());
		assertEquals("https://api.example.com/api/public/deliveries/tok", body.get("redirect_purchase_url").asText());
		assertEquals("enabled", body.get("global_affiliate_status").asText());
	}

	@Test
	void createsOneTimePlanAndReturnsCheckoutLink() throws Exception {
		response = "{\"id\":\"plan_1\",\"purchase_url\":\"https://whop.com/checkout/plan_1\"}";
		WhopApiClient.WhopPlan plan = client.createPlan(
				"key-123456", "prod_1", "A very long plan title that exceeds thirty characters", new BigDecimal("29.00"), "USD");
		assertEquals("https://whop.com/checkout/plan_1", plan.purchaseUrl());
		JsonNode body = mapper.readTree(seen.get(0).body());
		assertEquals("one_time", body.get("billing_type").asText());
		assertEquals("buy_now", body.get("release_method").asText());
		assertEquals("usd", body.get("currency").asText());
		assertEquals(29.0, body.get("initial_price").asDouble());
		assertEquals("prod_1", body.get("product_id").asText());
		assertTrue(body.get("title").asText().length() <= 30);
	}

	@Test
	void createsWebhookAndReturnsSecret() {
		response = "{\"id\":\"hook_1\",\"webhook_secret\":\"ws_abc\"}";
		WhopApiClient.WhopWebhook webhook = client.createWebhook(
				"key-123456", "https://api.example.com/api/webhooks/whop/tok", "Relay sales", List.of("payment.succeeded"));
		assertEquals("hook_1", webhook.id());
		assertEquals("ws_abc", webhook.secret());
	}

	@Test
	void rejectedKeyBecomesBusinessRuleError() {
		status = 401;
		response = "{\"message\":\"nope\"}";
		assertThrows(BusinessRuleException.class, () -> client.getCurrentAccount("key-123456"));
	}

	@Test
	void serverErrorBecomesBadGatewayWithoutLeakingKey() {
		status = 500;
		response = "{\"message\":\"boom\"}";
		BadGatewayException error = assertThrows(BadGatewayException.class,
				() -> client.createProduct("secret-key-xyz", "T", null, null, null));
		assertTrue(error.getMessage().contains("boom"));
		assertTrue(!error.getMessage().contains("secret-key-xyz"));
	}

	@Test
	void missingKeyIsRejectedBeforeAnyRequest() {
		assertThrows(BusinessRuleException.class, () -> client.getCurrentAccount(" "));
		assertEquals(0, seen.size());
	}
}
