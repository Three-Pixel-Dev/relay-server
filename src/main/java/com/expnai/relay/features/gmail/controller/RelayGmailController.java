package com.expnai.relay.features.gmail.controller;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

import com.expnai.relay.common.web.ApiResponse;
import com.expnai.relay.features.gmail.dto.response.RelayGmailAuthUrlResponse;
import com.expnai.relay.features.gmail.dto.response.RelayGmailMailboxResponse;
import com.expnai.relay.features.gmail.service.RelayGmailMailboxService;
import com.expnai.relay.features.gmail.serviceImpl.RelayGmailMailboxServiceImpl;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/gmail")
@RequiredArgsConstructor
@Tag(name = "Gmail")
public class RelayGmailController {

	private static final Logger log = LoggerFactory.getLogger(RelayGmailController.class);

	private final RelayGmailMailboxService mailboxService;
	private final RelayGmailMailboxServiceImpl mailboxServiceImpl;

	@Value("${relay.gmail.frontend-redirect-base:http://localhost:5173}")
	private String frontendRedirectBase;

	@GetMapping("/mailboxes")
	@Operation(summary = "List connected Gmail mailboxes for the current user")
	public ApiResponse<List<RelayGmailMailboxResponse>> list() {
		return mailboxService.listMine();
	}

	@GetMapping("/mailboxes/{id}")
	@Operation(summary = "Get one Gmail mailbox (owner only)")
	public ApiResponse<RelayGmailMailboxResponse> get(@PathVariable Long id) {
		return mailboxService.get(id);
	}

	@PostMapping("/oauth/start")
	@Operation(summary = "Start Gmail OAuth (returns Google authorization URL)")
	public ApiResponse<RelayGmailAuthUrlResponse> start() {
		return mailboxService.startConnect();
	}

	@GetMapping("/oauth/callback")
	@Operation(summary = "OAuth redirect callback — exchanges code and stores encrypted refresh token")
	public RedirectView callback(
			@RequestParam(value = "code", required = false) String code,
			@RequestParam(value = "state", required = false) String state,
			@RequestParam(value = "error", required = false) String error) {
		if (code == null || state == null || error != null) {
			return new RedirectView(failureRedirect());
		}
		try {
			return new RedirectView(mailboxServiceImpl.completeConnectRedirect(code, state, frontendRedirectBase));
		}
		catch (RuntimeException ex) {
			log.warn("Gmail OAuth callback failed: {}", ex.getClass().getSimpleName());
			return new RedirectView(failureRedirect());
		}
	}

	private String failureRedirect() {
		return UriComponentsBuilder.fromUriString(frontendRedirectBase)
				.path("/connections")
				.queryParam("gmail", "error")
				.build()
				.toUriString();
	}

	@PostMapping("/oauth/complete")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Complete Gmail OAuth with authorization code (SPA-friendly)")
	public ApiResponse<RelayGmailMailboxResponse> complete(
			@RequestParam("code") String code,
			@RequestParam("state") String state) {
		return mailboxService.completeConnect(code, state);
	}

	@DeleteMapping("/mailboxes/{id}")
	@Operation(summary = "Disconnect a Gmail mailbox")
	public ApiResponse<Void> disconnect(@PathVariable Long id) {
		return mailboxService.disconnect(id);
	}
}
