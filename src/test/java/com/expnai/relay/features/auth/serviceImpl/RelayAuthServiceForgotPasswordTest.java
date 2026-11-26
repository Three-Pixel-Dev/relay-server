package com.expnai.relay.features.auth.serviceImpl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.expnai.relay.common.exception.BusinessRuleException;
import com.expnai.relay.common.security.RelayJwtService;
import com.expnai.relay.common.web.ApiResponse;
import com.expnai.relay.domain.entity.RelayPasswordResetToken;
import com.expnai.relay.domain.entity.RelayUser;
import com.expnai.relay.domain.enums.RelayUserRole;
import com.expnai.relay.domain.repository.RelayPasswordResetTokenRepository;
import com.expnai.relay.domain.repository.RelaySubscriptionRepository;
import com.expnai.relay.domain.repository.RelayUserRepository;
import com.expnai.relay.domain.repository.RelayUserSessionRepository;
import com.expnai.relay.features.auth.dto.request.RelayForgotPasswordRequest;
import com.expnai.relay.features.auth.dto.request.RelayResetPasswordRequest;
import com.expnai.relay.features.auth.dto.response.RelayAuthMessageResponse;
import com.expnai.relay.features.auth.mail.RelayAuthMailService;
import com.expnai.relay.features.auth.mapper.RelayAuthMapper;
import com.expnai.relay.features.auth.service.GoogleIdTokenVerifierService;

class RelayAuthServiceForgotPasswordTest {

	private final RelayUserRepository userRepository = mock(RelayUserRepository.class);
	private final RelayUserSessionRepository sessionRepository = mock(RelayUserSessionRepository.class);
	private final RelaySubscriptionRepository subscriptionRepository = mock(RelaySubscriptionRepository.class);
	private final RelayPasswordResetTokenRepository passwordResetTokenRepository =
			mock(RelayPasswordResetTokenRepository.class);
	private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
	private final RelayAuthMailService authMailService = mock(RelayAuthMailService.class);

	private RelayAuthServiceImpl service;

	@BeforeEach
	void setUp() {
		service = new RelayAuthServiceImpl(
				userRepository,
				sessionRepository,
				subscriptionRepository,
				passwordResetTokenRepository,
				passwordEncoder,
				mock(AuthenticationManager.class),
				mock(UserDetailsService.class),
				mock(RelayJwtService.class),
				mock(RelayAuthMapper.class),
				mock(GoogleIdTokenVerifierService.class),
				authMailService);
		ReflectionTestUtils.setField(service, "frontendBaseUrl", "https://dev-relay.saungera.com");
		ReflectionTestUtils.setField(service, "passwordResetTtlMinutes", 60L);
		when(passwordResetTokenRepository.save(any(RelayPasswordResetToken.class)))
				.thenAnswer(inv -> inv.getArgument(0));
		when(passwordResetTokenRepository.findByUserIdAndUsedAtIsNull(any()))
				.thenReturn(List.of());
	}

	@Test
	void forgotPassword_unknownEmail_returnsGenericAndSkipsMail() {
		when(userRepository.findByEmailIgnoreCase("missing@example.com")).thenReturn(Optional.empty());

		ApiResponse<RelayAuthMessageResponse> response = service.forgotPassword(request("missing@example.com"));

		assertTrue(response.isSuccess());
		assertTrue(response.getMessage().toLowerCase().contains("if an account exists"));
		verify(authMailService, never()).sendPasswordReset(anyString(), anyString());
		verify(passwordResetTokenRepository, never()).save(any());
	}

	@Test
	void forgotPassword_googleOnly_returnsGenericAndSkipsMail() {
		RelayUser user = user("google@example.com", null);
		when(userRepository.findByEmailIgnoreCase("google@example.com")).thenReturn(Optional.of(user));

		ApiResponse<RelayAuthMessageResponse> response = service.forgotPassword(request("google@example.com"));

		assertTrue(response.isSuccess());
		verify(authMailService, never()).sendPasswordReset(anyString(), anyString());
		verify(passwordResetTokenRepository, never()).save(any());
	}

	@Test
	void forgotPassword_passwordUser_sendsResetEmail() {
		RelayUser user = user("user@example.com", "hash");
		when(userRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(Optional.of(user));

		ApiResponse<RelayAuthMessageResponse> response = service.forgotPassword(request("user@example.com"));

		assertTrue(response.isSuccess());
		ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
		verify(authMailService).sendPasswordReset(eq("user@example.com"), urlCaptor.capture());
		assertTrue(urlCaptor.getValue().startsWith("https://dev-relay.saungera.com/reset-password?token="));
		verify(passwordResetTokenRepository).save(any(RelayPasswordResetToken.class));
	}

	@Test
	void resetPassword_expiredToken_fails() {
		RelayPasswordResetToken token = new RelayPasswordResetToken();
		token.setUser(user("user@example.com", "old"));
		token.setExpiresAt(Instant.now().minusSeconds(60));
		token.setTokenHash("unused");
		when(passwordResetTokenRepository.findByTokenHashAndUsedAtIsNull(anyString()))
				.thenReturn(Optional.of(token));

		RelayResetPasswordRequest reset = new RelayResetPasswordRequest();
		reset.setToken("a".repeat(43));
		reset.setNewPassword("newpassword1");

		BusinessRuleException ex = assertThrows(BusinessRuleException.class, () -> service.resetPassword(reset));
		assertTrue(ex.getMessage().toLowerCase().contains("expired"));
	}

	private static RelayForgotPasswordRequest request(String email) {
		RelayForgotPasswordRequest req = new RelayForgotPasswordRequest();
		req.setEmail(email);
		return req;
	}

	private static RelayUser user(String email, String passwordHash) {
		RelayUser user = new RelayUser();
		user.setId(42L);
		user.setEmail(email);
		user.setPasswordHash(passwordHash);
		user.setRole(RelayUserRole.USER);
		user.setEnable(true);
		user.setTokenVersion(0L);
		return user;
	}
}
