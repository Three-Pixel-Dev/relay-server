package com.expnai.relay.features.auth.controller;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.expnai.relay.common.web.ApiResponse;
import com.expnai.relay.features.auth.dto.request.RelayClientInfo;
import com.expnai.relay.features.auth.dto.request.RelayGoogleLoginRequest;
import com.expnai.relay.features.auth.dto.request.RelayLoginRequest;
import com.expnai.relay.features.auth.dto.request.RelayRefreshTokenRequest;
import com.expnai.relay.features.auth.dto.request.RelaySignupRequest;
import com.expnai.relay.features.auth.dto.request.RelayUpdateProfileRequest;
import com.expnai.relay.features.auth.dto.response.RelayAuthMessageResponse;
import com.expnai.relay.features.auth.dto.response.RelayLoginResponse;
import com.expnai.relay.features.auth.dto.response.RelayMeResponse;
import com.expnai.relay.features.auth.dto.response.RelaySessionResponse;
import com.expnai.relay.features.auth.service.RelayAuthService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Auth")
public class RelayAuthController {

	private final RelayAuthService authService;

	@PostMapping("/signup")
	@Operation(summary = "Create account and return JWT (no trial)")
	public ApiResponse<RelayLoginResponse> signup(
			@Valid @RequestBody RelaySignupRequest request,
			HttpServletRequest httpRequest) {
		return authService.signup(request, clientInfo(httpRequest));
	}

	@PostMapping("/login")
	@Operation(summary = "Login with email and password")
	public ApiResponse<RelayLoginResponse> login(
			@Valid @RequestBody RelayLoginRequest request,
			HttpServletRequest httpRequest) {
		return authService.login(request, clientInfo(httpRequest));
	}

	@PostMapping("/google")
	@Operation(summary = "Login or signup with Google ID token")
	public ApiResponse<RelayLoginResponse> loginWithGoogle(
			@Valid @RequestBody RelayGoogleLoginRequest request,
			HttpServletRequest httpRequest) {
		return authService.loginWithGoogle(request, clientInfo(httpRequest));
	}

	@PostMapping("/refresh-token")
	@Operation(summary = "Refresh access token")
	public ApiResponse<RelayLoginResponse> refreshToken(
			@Valid @RequestBody RelayRefreshTokenRequest request,
			HttpServletRequest httpRequest) {
		return authService.refreshToken(request, clientInfo(httpRequest));
	}

	@GetMapping("/me")
	@Operation(summary = "Current authenticated user")
	public ApiResponse<RelayMeResponse> me() {
		return authService.me();
	}

	@PutMapping("/profile")
	@Operation(summary = "Update profile name")
	public ApiResponse<RelayMeResponse> updateProfile(@Valid @RequestBody RelayUpdateProfileRequest request) {
		return authService.updateProfile(request);
	}

	@GetMapping("/sessions")
	@Operation(summary = "List active sessions")
	public ApiResponse<List<RelaySessionResponse>> listSessions() {
		return authService.listSessions();
	}

	@DeleteMapping("/sessions/{sessionId}")
	@Operation(summary = "Sign out a specific session")
	public ApiResponse<RelayAuthMessageResponse> revokeSession(@PathVariable Long sessionId) {
		return authService.revokeSession(sessionId);
	}

	@PostMapping("/logout")
	@Operation(summary = "Sign out current session")
	public ApiResponse<RelayAuthMessageResponse> logout() {
		return authService.logout();
	}

	@PostMapping("/logout-all")
	@Operation(summary = "Sign out all sessions")
	public ApiResponse<RelayAuthMessageResponse> logoutAll() {
		return authService.logoutAll();
	}

	private RelayClientInfo clientInfo(HttpServletRequest request) {
		String forwarded = request.getHeader("X-Forwarded-For");
		String ip = (forwarded != null && !forwarded.isBlank())
				? forwarded.split(",")[0].trim()
				: request.getRemoteAddr();
		return new RelayClientInfo(request.getHeader("User-Agent"), ip);
	}
}
