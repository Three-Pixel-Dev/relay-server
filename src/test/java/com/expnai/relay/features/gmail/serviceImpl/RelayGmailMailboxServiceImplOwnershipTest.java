package com.expnai.relay.features.gmail.serviceImpl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.expnai.relay.common.exception.ResourceNotFoundException;
import com.expnai.relay.domain.entity.RelayUser;
import com.expnai.relay.domain.enums.RelayUserRole;
import com.expnai.relay.domain.repository.RelayGmailMailboxRepository;
import com.expnai.relay.domain.repository.RelayUserRepository;
import com.expnai.relay.features.auth.security.RelayUserPrincipal;
import com.expnai.relay.features.gmail.client.GmailApiClient;
import com.expnai.relay.features.gmail.oauth.GmailOAuthService;
import com.expnai.relay.features.gmail.service.GmailTokenService;
import com.expnai.relay.features.gmail.sync.GmailHistorySyncService;
import com.expnai.relay.common.security.TokenEncryptionService;

class RelayGmailMailboxServiceImplOwnershipTest {

	private RelayUserRepository userRepository;
	private RelayGmailMailboxRepository mailboxRepository;
	private RelayGmailMailboxServiceImpl service;

	@BeforeEach
	void setUp() {
		userRepository = mock(RelayUserRepository.class);
		mailboxRepository = mock(RelayGmailMailboxRepository.class);
		service = new RelayGmailMailboxServiceImpl(userRepository, mailboxRepository,
				mock(GmailOAuthService.class), mock(GmailTokenService.class), mock(TokenEncryptionService.class),
				mock(GmailApiClient.class), mock(GmailHistorySyncService.class));

		RelayUser user = new RelayUser();
		user.setId(1L);
		user.setEmail("me@example.com");
		user.setRole(RelayUserRole.USER);
		when(userRepository.findById(1L)).thenReturn(Optional.of(user));
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(new RelayUserPrincipal(user), null, java.util.List.of()));
	}

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void userCannotReadAnotherUsersMailbox() {
		when(mailboxRepository.findByIdAndUserId(99L, 1L)).thenReturn(Optional.empty());
		assertThrows(ResourceNotFoundException.class, () -> service.get(99L));
	}

	@Test
	void userCannotDisconnectAnotherUsersMailbox() {
		when(mailboxRepository.findByIdAndUserId(99L, 1L)).thenReturn(Optional.empty());
		assertThrows(ResourceNotFoundException.class, () -> service.disconnect(99L));
	}
}
