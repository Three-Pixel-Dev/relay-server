package com.expnai.relay.features.auth.serviceImpl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.expnai.relay.common.exception.BusinessRuleException;
import com.expnai.relay.common.exception.InvalidSessionException;
import com.expnai.relay.common.exception.ResourceConflictException;
import com.expnai.relay.common.exception.ResourceNotFoundException;
import com.expnai.relay.common.security.RelayJwtService;
import com.expnai.relay.common.web.ApiResponse;
import com.expnai.relay.domain.entity.RelaySubscription;
import com.expnai.relay.domain.entity.RelayUser;
import com.expnai.relay.domain.entity.RelayUserSession;
import com.expnai.relay.domain.enums.RelaySubscriptionStatus;
import com.expnai.relay.domain.enums.RelayUserRole;
import com.expnai.relay.domain.repository.RelaySubscriptionRepository;
import com.expnai.relay.domain.repository.RelayUserRepository;
import com.expnai.relay.domain.repository.RelayUserSessionRepository;
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
import com.expnai.relay.features.auth.mapper.RelayAuthMapper;
import com.expnai.relay.features.auth.security.RelayUserPrincipal;
import com.expnai.relay.features.auth.service.GoogleIdTokenVerifierService;
import com.expnai.relay.features.auth.service.GoogleIdTokenVerifierService.VerifiedGoogleIdentity;
import com.expnai.relay.features.auth.service.RelayAuthService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RelayAuthServiceImpl implements RelayAuthService {

	private static final List<RelaySubscriptionStatus> ACTIVE_STATUSES = List.of(
			RelaySubscriptionStatus.ACTIVE,
			RelaySubscriptionStatus.PAST_DUE);

	private final RelayUserRepository userRepository;
	private final RelayUserSessionRepository sessionRepository;
	private final RelaySubscriptionRepository subscriptionRepository;
	private final PasswordEncoder passwordEncoder;
	private final AuthenticationManager authenticationManager;
	private final UserDetailsService userDetailsService;
	private final RelayJwtService jwtService;
	private final RelayAuthMapper authMapper;
	private final GoogleIdTokenVerifierService googleIdTokenVerifierService;

	@Override
	@Transactional
	public ApiResponse<RelayLoginResponse> signup(RelaySignupRequest request, RelayClientInfo clientInfo) {
		String email = normalizeEmail(request.getEmail());
		if (userRepository.existsByEmailIgnoreCase(email)) {
			throw new ResourceConflictException("An account with this email already exists");
		}

		RelayUser user = new RelayUser();
		user.setEmail(email);
		user.setName(request.getName().trim());
		user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
		user.setRole(RelayUserRole.USER);
		userRepository.save(user);

		RelayUserSession session = createSession(user, clientInfo);
		return ApiResponse.created("Account created", issueTokens(user, user.getTokenVersion(), session));
	}

	@Override
	@Transactional
	public ApiResponse<RelayLoginResponse> login(RelayLoginRequest request, RelayClientInfo clientInfo) {
		String email = normalizeEmail(request.getEmail());
		RelayUser existing = userRepository.findByEmailIgnoreCase(email).orElse(null);
		if (existing != null && (existing.getPasswordHash() == null || existing.getPasswordHash().isBlank())) {
			throw new BusinessRuleException("This account uses Google Sign-In. Please continue with Google.");
		}
		authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(email, request.getPassword()));

		RelayUser user = requireUser(email);
		RelayUserSession session = createSession(user, clientInfo);
		return ApiResponse.ok("Login successful", issueTokens(user, user.getTokenVersion(), session));
	}

	@Override
	@Transactional
	public ApiResponse<RelayLoginResponse> loginWithGoogle(RelayGoogleLoginRequest request, RelayClientInfo clientInfo) {
		VerifiedGoogleIdentity identity = googleIdTokenVerifierService.verify(request.getIdToken());

		String googleId = identity.googleId();
		String email = identity.email();
		String displayName = identity.displayName();
		if (displayName == null || displayName.isBlank()) {
			displayName = email != null && email.contains("@")
					? email.substring(0, email.indexOf('@'))
					: "Google User";
		}

		Optional<RelayUser> byGoogle = userRepository.findByGoogleId(googleId);
		RelayUser user;

		if (byGoogle.isPresent()) {
			user = byGoogle.get();
			if (identity.pictureUrl() != null && !identity.pictureUrl().isBlank()) {
				user.setAvatarUrl(identity.pictureUrl());
				user = userRepository.save(user);
			}
		}
		else if (email != null && !email.isBlank()) {
			Optional<RelayUser> byEmail = userRepository.findByEmailIgnoreCase(email);
			if (byEmail.isPresent()) {
				user = byEmail.get();
				if (user.getGoogleId() != null && !user.getGoogleId().equals(googleId)) {
					throw new BadCredentialsException("This email is already linked to another Google account.");
				}
				user.setGoogleId(googleId);
				if (user.getName() == null || user.getName().isBlank()) {
					user.setName(displayName);
				}
				if (identity.pictureUrl() != null && !identity.pictureUrl().isBlank()) {
					user.setAvatarUrl(identity.pictureUrl());
				}
				user = userRepository.save(user);
			}
			else {
				user = createGoogleUser(googleId, email, displayName, identity.pictureUrl());
			}
		}
		else {
			String syntheticEmail = "google_" + googleId + "@google.relay.local";
			user = createGoogleUser(googleId, syntheticEmail, displayName, identity.pictureUrl());
		}

		if (!user.isEnable()) {
			throw new BadCredentialsException("Account is deactivated. Please contact support.");
		}

		RelayUserSession session = createSession(user, clientInfo);
		return ApiResponse.ok("Google login successful", issueTokens(user, user.getTokenVersion(), session));
	}

	private RelayUser createGoogleUser(String googleId, String email, String displayName, String pictureUrl) {
		try {
			RelayUser user = new RelayUser();
			user.setGoogleId(googleId);
			user.setEmail(email);
			user.setName(displayName);
			user.setAvatarUrl(pictureUrl);
			user.setRole(RelayUserRole.USER);
			user.setPasswordHash(null);
			return userRepository.save(user);
		}
		catch (DataIntegrityViolationException race) {
			return userRepository.findByGoogleId(googleId)
					.or(() -> userRepository.findByEmailIgnoreCase(email))
					.orElseThrow(() -> new BadCredentialsException("Could not complete Google Sign-In. Please try again."));
		}
	}

	@Override
	@Transactional
	public ApiResponse<RelayLoginResponse> refreshToken(RelayRefreshTokenRequest request, RelayClientInfo clientInfo) {
		String refreshToken = request.getRefreshToken();
		if (!jwtService.isRefreshToken(refreshToken)) {
			throw new InvalidSessionException("Invalid refresh token");
		}

		String email = jwtService.extractUsername(refreshToken);
		UserDetails userDetails = userDetailsService.loadUserByUsername(email);
		if (!jwtService.validateToken(refreshToken, userDetails)) {
			throw new InvalidSessionException("Refresh token expired or invalid");
		}

		RelayUser user = requireUser(email);
		Long tokenVersion = jwtService.extractTokenVersion(refreshToken);
		if (tokenVersion == null || tokenVersion != user.getTokenVersion()) {
			throw new InvalidSessionException("Refresh token is no longer valid");
		}

		Long sessionId = jwtService.extractSessionId(refreshToken);
		RelayUserSession session = requireActiveSession(sessionId, user.getId());
		if (!sha256(refreshToken).equals(session.getRefreshTokenHash())) {
			throw new InvalidSessionException("Refresh token is no longer valid");
		}

		session.setLastActiveAt(Instant.now());
		if (clientInfo != null) {
			session.setUserAgent(trim(clientInfo.userAgent(), 500));
			session.setIpAddress(trim(clientInfo.ipAddress(), 64));
			session.setDeviceLabel(deviceLabel(clientInfo.userAgent()));
		}
		sessionRepository.save(session);

		return ApiResponse.ok("Token refreshed", issueTokens(user, user.getTokenVersion(), session));
	}

	@Override
	@Transactional(readOnly = true)
	public ApiResponse<RelayMeResponse> me() {
		RelayUserPrincipal principal = currentPrincipal();
		RelayUser user = requireUser(principal.getEmail());
		RelaySubscription subscription = currentSubscription(user.getId());
		return ApiResponse.ok("OK", authMapper.toMe(user, principal.getSessionId(), subscription));
	}

	@Override
	@Transactional
	public ApiResponse<RelayMeResponse> updateProfile(RelayUpdateProfileRequest request) {
		RelayUserPrincipal principal = currentPrincipal();
		RelayUser user = requireUser(principal.getEmail());
		user.setName(request.getName().trim());
		userRepository.save(user);
		RelaySubscription subscription = currentSubscription(user.getId());
		return ApiResponse.ok("Profile saved", authMapper.toMe(user, principal.getSessionId(), subscription));
	}

	@Override
	@Transactional(readOnly = true)
	public ApiResponse<List<RelaySessionResponse>> listSessions() {
		RelayUserPrincipal principal = currentPrincipal();
		Long currentSessionId = principal.getSessionId();
		List<RelaySessionResponse> sessions = sessionRepository
				.findByUserIdAndRevokedAtIsNullOrderByLastActiveAtDesc(principal.getId())
				.stream()
				.map(session -> new RelaySessionResponse(
						session.getId(),
						session.getDeviceLabel(),
						session.getIpAddress(),
						session.getCreatedAt(),
						session.getLastActiveAt(),
						currentSessionId != null && currentSessionId.equals(session.getId())))
				.toList();
		return ApiResponse.ok("OK", sessions);
	}

	@Override
	@Transactional
	public ApiResponse<RelayAuthMessageResponse> revokeSession(Long sessionId) {
		RelayUserPrincipal principal = currentPrincipal();
		RelayUserSession session = sessionRepository
				.findByIdAndUserIdAndRevokedAtIsNull(sessionId, principal.getId())
				.orElseThrow(() -> new ResourceNotFoundException("Session not found"));
		revoke(session);
		return ApiResponse.ok("Session signed out", new RelayAuthMessageResponse("Session signed out"));
	}

	@Override
	@Transactional
	public ApiResponse<RelayAuthMessageResponse> logout() {
		RelayUserPrincipal principal = currentPrincipal();
		if (principal.getSessionId() != null) {
			sessionRepository.findByIdAndUserIdAndRevokedAtIsNull(principal.getSessionId(), principal.getId())
					.ifPresent(this::revoke);
		}
		return ApiResponse.ok("Logged out", new RelayAuthMessageResponse("Logged out"));
	}

	@Override
	@Transactional
	public ApiResponse<RelayAuthMessageResponse> logoutAll() {
		RelayUserPrincipal principal = currentPrincipal();
		RelayUser user = requireUser(principal.getEmail());
		user.setTokenVersion(user.getTokenVersion() + 1);
		userRepository.save(user);
		revokeAllSessions(user.getId());
		return ApiResponse.ok("Logged out of all devices", new RelayAuthMessageResponse("Logged out of all devices"));
	}

	private RelaySubscription currentSubscription(Long userId) {
		return subscriptionRepository
				.findFirstByUserIdAndStatusInOrderByCreatedAtDesc(userId, ACTIVE_STATUSES)
				.orElse(null);
	}

	private void revokeAllSessions(Long userId) {
		for (RelayUserSession session : sessionRepository.findByUserIdAndRevokedAtIsNullOrderByLastActiveAtDesc(userId)) {
			revoke(session);
		}
	}

	private void revoke(RelayUserSession session) {
		session.setRevokedAt(Instant.now());
		sessionRepository.save(session);
	}

	private RelayLoginResponse issueTokens(RelayUser user, long tokenVersion, RelayUserSession session) {
		UserDetails userDetails = userDetailsService.loadUserByUsername(user.getEmail());
		String accessToken = jwtService.generateAccessToken(userDetails, tokenVersion, session.getId());
		String refreshToken = jwtService.generateRefreshToken(userDetails, tokenVersion, session.getId());
		session.setRefreshTokenHash(sha256(refreshToken));
		sessionRepository.save(session);
		return new RelayLoginResponse(
				accessToken,
				refreshToken,
				user.getId(),
				user.getEmail(),
				user.getName(),
				user.getRole().name());
	}

	private RelayUserSession createSession(RelayUser user, RelayClientInfo clientInfo) {
		RelayUserSession session = new RelayUserSession();
		session.setUser(user);
		session.setRefreshTokenHash("pending");
		session.setUserAgent(clientInfo == null ? null : trim(clientInfo.userAgent(), 500));
		session.setIpAddress(clientInfo == null ? null : trim(clientInfo.ipAddress(), 64));
		session.setDeviceLabel(clientInfo == null ? "Unknown device" : deviceLabel(clientInfo.userAgent()));
		session.setLastActiveAt(Instant.now());
		return sessionRepository.save(session);
	}

	private RelayUserSession requireActiveSession(Long sessionId, Long userId) {
		if (sessionId == null) {
			throw new InvalidSessionException("Refresh token is no longer valid");
		}
		return sessionRepository.findByIdAndUserIdAndRevokedAtIsNull(sessionId, userId)
				.orElseThrow(() -> new InvalidSessionException("Refresh token is no longer valid"));
	}

	private RelayUser requireUser(String email) {
		return userRepository.findByEmailIgnoreCase(email)
				.orElseThrow(() -> new ResourceNotFoundException("User not found"));
	}

	private RelayUserPrincipal currentPrincipal() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof RelayUserPrincipal principal)) {
			throw new InvalidSessionException("Not authenticated");
		}
		return principal;
	}

	private String normalizeEmail(String email) {
		return email == null ? null : email.trim().toLowerCase();
	}

	private String trim(String value, int max) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
	}

	private String deviceLabel(String userAgent) {
		if (userAgent == null || userAgent.isBlank()) {
			return "Unknown device";
		}
		String ua = userAgent.toLowerCase();
		if (ua.contains("iphone") || ua.contains("ipad")) {
			return "iOS";
		}
		if (ua.contains("android")) {
			return "Android";
		}
		if (ua.contains("mac os") || ua.contains("macintosh")) {
			return "macOS";
		}
		if (ua.contains("windows")) {
			return "Windows";
		}
		return trim(userAgent, 150);
	}

	private String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 not available", ex);
		}
	}
}
