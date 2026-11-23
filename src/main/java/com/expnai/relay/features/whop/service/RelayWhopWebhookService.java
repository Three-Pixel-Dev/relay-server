package com.expnai.relay.features.whop.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.expnai.relay.common.exception.BusinessRuleException;
import com.expnai.relay.common.exception.InvalidSessionException;
import com.expnai.relay.common.exception.ResourceNotFoundException;
import com.expnai.relay.common.security.CredentialEncryptionService;
import com.expnai.relay.domain.entity.RelayOrder;
import com.expnai.relay.domain.entity.RelayServiceConnection;
import com.expnai.relay.domain.entity.RelayWhopProduct;
import com.expnai.relay.domain.repository.RelayOrderRepository;
import com.expnai.relay.domain.repository.RelayServiceConnectionRepository;
import com.expnai.relay.domain.repository.RelayWhopProductRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/** Verifies Whop webhooks for a connection and records successful payments as orders. */
@Service
@RequiredArgsConstructor
public class RelayWhopWebhookService {

	private static final Logger log = LoggerFactory.getLogger(RelayWhopWebhookService.class);
	static final String ORDER_SOURCE = RelayWhopCommerceReader.PLATFORM;

	private final RelayServiceConnectionRepository connectionRepository;
	private final RelayWhopProductRepository whopProductRepository;
	private final RelayOrderRepository orderRepository;
	private final CredentialEncryptionService credentialEncryptionService;
	private final WhopWebhookVerifier verifier;
	private final ObjectMapper objectMapper;

	@Transactional
	public void handle(String token, String webhookId, String timestamp, String signature, String body) {
		RelayServiceConnection connection = connectionRepository.findByWebhookToken(token)
				.orElseThrow(() -> new ResourceNotFoundException("Not found"));
		String secret = connection.getEncryptedWebhookSecret() == null
				? null
				: credentialEncryptionService.decrypt(connection.getEncryptedWebhookSecret());
		if (!verifier.verify(secret, webhookId, timestamp, signature, body, Instant.now())) {
			throw new InvalidSessionException("Invalid webhook signature");
		}

		JsonNode event;
		try {
			event = objectMapper.readTree(body);
		}
		catch (IOException ex) {
			throw new BusinessRuleException("Webhook body is not valid JSON.");
		}
		if (!"payment.succeeded".equals(text(event, "type"))) {
			return;
		}
		recordPayment(connection, event.path("data"));
	}

	private void recordPayment(RelayServiceConnection connection, JsonNode payment) {
		String paymentId = text(payment, "id");
		String externalProductId = text(payment, "product_id");
		if (paymentId == null || externalProductId == null) {
			return;
		}
		RelayWhopProduct whop = whopProductRepository.findFirstByExternalProductId(externalProductId).orElse(null);
		if (whop == null || !whop.getProduct().getUser().getId().equals(connection.getUser().getId())) {
			log.info("Ignoring Whop payment for a product Relay did not publish for this account");
			return;
		}
		if (orderRepository.existsBySourceAndExternalId(ORDER_SOURCE, paymentId)) {
			return;
		}
		BigDecimal amount = money(payment.path("usd_total"));
		if (amount == null) {
			amount = money(payment.path("total"));
		}
		if (amount == null) {
			log.warn("Whop payment {} has no readable amount; skipping", paymentId);
			return;
		}
		RelayOrder order = new RelayOrder();
		order.setProduct(whop.getProduct());
		order.setAmount(amount);
		order.setSource(ORDER_SOURCE);
		order.setExternalId(paymentId);
		order.setOrderedAt(instant(text(payment, "paid_at"), text(payment, "created_at")));
		orderRepository.save(order);
	}

	private static BigDecimal money(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return null;
		}
		String raw = node.isObject() ? text(node, "amount") : (node.isValueNode() ? node.asText() : null);
		if (raw == null) {
			return null;
		}
		try {
			return new BigDecimal(raw.trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private static Instant instant(String... candidates) {
		for (String candidate : candidates) {
			if (candidate == null) {
				continue;
			}
			try {
				return OffsetDateTime.parse(candidate).toInstant();
			}
			catch (DateTimeParseException ignored) {
				// try the next candidate
			}
		}
		return Instant.now();
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node == null ? null : node.get(field);
		if (value == null || value.isNull() || !value.isValueNode()) {
			return null;
		}
		String text = value.asText();
		return text == null || text.isBlank() ? null : text;
	}
}
