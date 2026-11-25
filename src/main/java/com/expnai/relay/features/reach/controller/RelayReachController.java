package com.expnai.relay.features.reach.controller;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.UriComponentsBuilder;

import com.expnai.relay.common.web.ApiResponse;
import com.expnai.relay.features.reach.config.ReachProperties;
import com.expnai.relay.features.reach.service.RelayReachService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@Tag(name = "Reach")
public class RelayReachController {

	private static final Logger log = LoggerFactory.getLogger(RelayReachController.class);

	private final RelayReachService reachService;
	private final ReachProperties properties;

	@PostMapping("/api/{platform:youtube|tiktok|instagram}/oauth/start")
	@Operation(summary = "Start reach OAuth (youtube | tiktok | instagram)")
	public ApiResponse<Map<String, String>> start(@PathVariable("platform") String platform) {
		return reachService.startConnect(platform);
	}

	@GetMapping("/api/{platform:youtube|tiktok|instagram}/oauth/callback")
	@Operation(summary = "Reach OAuth redirect callback")
	public RedirectView callback(
			@PathVariable("platform") String platform,
			@RequestParam(value = "code", required = false) String code,
			@RequestParam(value = "state", required = false) String state,
			@RequestParam(value = "error", required = false) String error) {
		if (code == null || state == null || error != null) {
			return new RedirectView(failureRedirect(platform));
		}
		try {
			return new RedirectView(reachService.completeConnectRedirect(platform, code, state));
		}
		catch (RuntimeException ex) {
			log.warn("{} OAuth callback failed: {}", platform, ex.getClass().getSimpleName());
			return new RedirectView(failureRedirect(platform));
		}
	}

	@PostMapping("/api/{platform:youtube|tiktok|instagram}/oauth/complete")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Complete reach OAuth with code (SPA-friendly)")
	public ApiResponse<Map<String, Object>> complete(
			@PathVariable("platform") String platform,
			@RequestParam("code") String code,
			@RequestParam("state") String state) {
		return reachService.completeConnect(platform, code, state);
	}

	private String failureRedirect(String platform) {
		return UriComponentsBuilder.fromUriString(properties.getFrontendRedirectBase())
				.path("/connections")
				.queryParam(platform, "error")
				.build()
				.toUriString();
	}
}
