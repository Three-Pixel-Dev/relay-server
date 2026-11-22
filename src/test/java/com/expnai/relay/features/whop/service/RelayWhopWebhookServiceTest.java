package com.expnai.relay.features.whop.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.expnai.relay.common.exception.InvalidSessionException;
import com.expnai.relay.common.exception.ResourceNotFoundException;
import com.expnai.relay.common.security.CredentialEncryptionService;
import com.expnai.relay.domain.entity.RelayOrder;
import com.expnai.relay.domain.entity.RelayProduct;
import com.expnai.relay.domain.entity.RelayServiceConnection;
import com.expnai.relay.domain.entity.RelayUser;
import com.expnai.relay.domain.entity.RelayWhopProduct;
import com.expnai.relay.domain.repository.RelayOrderRepository;
import com.expnai.relay.domain.repository.RelayServiceConnectionRepository;
import com.expnai.relay.domain.repository.RelayWhopProductRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

class RelayWhopWebhookServiceTest {

	private static final String TOKEN = "tok";
	private static final String SECRET = "ws_secret";

	private RelayServiceConnectionRepository connections;
	private RelayWhopProductRepository whopProducts;
	private RelayOrderRepository orders;
	private CredentialEncryptionService encryption;
	private RelayWhopWebhookService service;
	private RelayServiceConnection connection;

	private static RelayUser user(long id) {
		RelayUser user = mock(RelayUser.class);
		when(user.getId()).thenReturn(id);
		return user;
	}

	private static String sign(String id, String ts, String body) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return "v1," + Base64.getEncoder().encodeToString(
				mac.doFinal((id + "." + ts + "." + body).getBytes(StandardCharsets.UTF_8)));
	}

	private static String payment(String productId) {
		return "{\"type\":\"payment.succeeded\",\"data\":{\"id\":\"pay_1\",\"product_id\":\"" + productId
				+ "\",\"usd_total\":{\"amount\":\"29.00\",\"currency\":\"usd\"},"
				+ "\"paid_at\":\"2026-11-22T10:00:00+07:00\"}}";
	}

	private RelayWhopProduct whopProductOwnedBy(long ownerId) {
		RelayUser owner = user(ownerId);
		RelayProduct product = mock(RelayProduct.class);
		when(product.getUser()).thenReturn(owner);
		RelayWhopProduct whop = new RelayWhopProduct();
		whop.setProduct(product);
		return whop;
	}

	@BeforeEach
	void setUp() {
		connections = mock(RelayServiceConnectionRepository.class);
		whopProducts = mock(RelayWhopProductRepository.class);
		orders = mock(RelayOrderRepository.class);
		encryption = mock(CredentialEncryptionService.class);
		service = new RelayWhopWebhookService(
				connections, whopProducts, orders, encryption, new WhopWebhookVerifier(), new ObjectMapper());

		connection = new RelayServiceConnection();
		connection.setUser(user(7L));
		connection.setEncryptedWebhookSecret("enc");
		when(encryption.decrypt("enc")).thenReturn(SECRET);
		when(connections.findByWebhookToken(TOKEN)).thenReturn(Optional.of(connection));
	}

	private void deliver(String body) throws Exception {
		String ts = String.valueOf(Instant.now().getEpochSecond());
		service.handle(TOKEN, "msg_1", ts, sign("msg_1", ts, body), body);
	}

	@Test
	void recordsPaymentAsOrder() throws Exception {
		RelayWhopProduct owned = whopProductOwnedBy(7L);
		when(whopProducts.findFirstByExternalProductId("prod_1")).thenReturn(Optional.of(owned));
		when(orders.existsBySourceAndExternalId("whop", "pay_1")).thenReturn(false);

		deliver(payment("prod_1"));

		ArgumentCaptor<RelayOrder> saved = ArgumentCaptor.forClass(RelayOrder.class);
		verify(orders).save(saved.capture());
		assertEquals(0, new BigDecimal("29.00").compareTo(saved.getValue().getAmount()));
		assertEquals("pay_1", saved.getValue().getExternalId());
		assertEquals("whop", saved.getValue().getSource());
		assertEquals(Instant.parse("2026-11-22T03:00:00Z"), saved.getValue().getOrderedAt());
	}

	@Test
	void duplicateDeliveryDoesNotCreateSecondOrder() throws Exception {
		RelayWhopProduct owned = whopProductOwnedBy(7L);
		when(whopProducts.findFirstByExternalProductId("prod_1")).thenReturn(Optional.of(owned));
		when(orders.existsBySourceAndExternalId("whop", "pay_1")).thenReturn(true);

		deliver(payment("prod_1"));

		verify(orders, never()).save(any());
	}

	@Test
	void ignoresPaymentForProductOwnedBySomeoneElse() throws Exception {
		RelayWhopProduct owned = whopProductOwnedBy(99L);
		when(whopProducts.findFirstByExternalProductId("prod_1")).thenReturn(Optional.of(owned));

		deliver(payment("prod_1"));

		verify(orders, never()).save(any());
	}

	@Test
	void ignoresOtherEventTypes() throws Exception {
		deliver("{\"type\":\"membership.activated\",\"data\":{}}");

		verify(orders, never()).save(any());
	}

	@Test
	void badSignatureIsRejected() {
		String ts = String.valueOf(Instant.now().getEpochSecond());
		assertThrows(InvalidSessionException.class,
				() -> service.handle(TOKEN, "msg_1", ts, "v1,AAAA", payment("prod_1")));
		verify(orders, never()).save(any());
	}

	@Test
	void unknownTokenIsNotFound() {
		when(connections.findByWebhookToken("nope")).thenReturn(Optional.empty());
		assertThrows(ResourceNotFoundException.class, () -> service.handle("nope", "id", "1", "sig", "{}"));
	}
}
