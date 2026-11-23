package com.expnai.relay.features.whop.controller;

import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.expnai.relay.features.whop.service.RelayWhopDeliveryService;
import com.expnai.relay.features.whop.service.RelayWhopWebhookService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/** Unauthenticated endpoints called by Whop (webhooks) and followed by buyers (downloads). */
@RestController
@RequiredArgsConstructor
@Tag(name = "Whop")
@RequestMapping("/api")
public class RelayWhopPublicController {

	private final RelayWhopWebhookService webhookService;
	private final RelayWhopDeliveryService deliveryService;

	@PostMapping("/webhooks/whop/{token}")
	@Operation(summary = "Whop webhook receiver (signature verified per connection)")
	public ResponseEntity<Void> webhook(
			@PathVariable String token,
			@RequestHeader(value = "webhook-id", required = false) String webhookId,
			@RequestHeader(value = "webhook-timestamp", required = false) String timestamp,
			@RequestHeader(value = "webhook-signature", required = false) String signature,
			@RequestBody String body) {
		webhookService.handle(token, webhookId, timestamp, signature, body);
		return ResponseEntity.ok().build();
	}

	@GetMapping("/public/deliveries/{token}")
	@Operation(summary = "Buyer download page shown after Whop checkout")
	public ResponseEntity<String> deliveryPage(@PathVariable String token) {
		return deliveryService.page(token);
	}

	@GetMapping("/public/deliveries/{token}/files/{fileId}")
	@Operation(summary = "Download one purchased file")
	public ResponseEntity<Resource> deliveryFile(@PathVariable String token, @PathVariable Long fileId) {
		return deliveryService.file(token, fileId);
	}
}
