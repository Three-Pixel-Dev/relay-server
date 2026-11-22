package com.expnai.relay.features.gmail.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
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
			@RequestParam("code") String code,
			@RequestParam("state") String state) {
		String redirect = mailboxServiceImpl.completeConnectRedirect(code, state, frontendRedirectBase);
		return new RedirectView(redirect);
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
