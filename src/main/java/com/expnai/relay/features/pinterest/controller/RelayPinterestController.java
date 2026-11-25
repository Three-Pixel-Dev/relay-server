package com.expnai.relay.features.pinterest.controller;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.UriComponentsBuilder;

import com.expnai.relay.common.web.ApiResponse;
import com.expnai.relay.features.pinterest.config.PinterestProperties;
import com.expnai.relay.features.pinterest.service.RelayPinterestService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/pinterest")
@RequiredArgsConstructor
@Tag(name = "Pinterest")
public class RelayPinterestController {

	private static final Logger log = LoggerFactory.getLogger(RelayPinterestController.class);

	private final RelayPinterestService pinterestService;
	private final PinterestProperties properties;

	@PostMapping("/oauth/start")
	@Operation(summary = "Start Pinterest OAuth (returns authorization URL)")
	public ApiResponse<Map<String, String>> start() {
		return pinterestService.startConnect();
	}

	@GetMapping("/oauth/callback")
	@Operation(summary = "OAuth redirect callback")
	public RedirectView callback(
			@RequestParam(value = "code", required = false) String code,
			@RequestParam(value = "state", required = false) String state,
			@RequestParam(value = "error", required = false) String error) {
		if (code == null || state == null || error != null) {
			return new RedirectView(failureRedirect());
		}
		try {
			return new RedirectView(pinterestService.completeConnectRedirect(code, state));
		}
		catch (RuntimeException ex) {
			log.warn("Pinterest OAuth callback failed: {}", ex.getClass().getSimpleName());
			return new RedirectView(failureRedirect());
		}
	}

	@PostMapping("/oauth/complete")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Complete Pinterest OAuth with authorization code (SPA-friendly)")
	public ApiResponse<Map<String, Object>> complete(
			@RequestParam("code") String code,
			@RequestParam("state") String state) {
		return pinterestService.completeConnect(code, state);
	}

	private String failureRedirect() {
		return UriComponentsBuilder.fromUriString(properties.getFrontendRedirectBase())
				.path("/connections")
				.queryParam("pinterest", "error")
				.build()
				.toUriString();
	}
}
