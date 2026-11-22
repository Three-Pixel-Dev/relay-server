package com.expnai.relay.features.gmail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.expnai.relay.domain.entity.RelayGmailMailbox;
import com.expnai.relay.domain.entity.RelayGmailProcessedMessage;
import com.expnai.relay.domain.entity.RelayOutreachMessage;
import com.expnai.relay.domain.enums.RelayGmailMailboxStatus;
import com.expnai.relay.domain.repository.RelayGmailMailboxRepository;
import com.expnai.relay.domain.repository.RelayGmailProcessedMessageRepository;
import com.expnai.relay.domain.repository.RelayOutreachMessageRepository;
import com.expnai.relay.features.gmail.client.GmailApiClient;
import com.expnai.relay.features.gmail.client.GmailApiException;
import com.expnai.relay.features.gmail.client.GmailHistoryPage;
import com.expnai.relay.features.gmail.client.GmailMessageView;
import com.expnai.relay.features.gmail.config.GmailProperties;
import com.expnai.relay.features.gmail.oauth.GmailReauthRequiredException;
import com.expnai.relay.features.gmail.reply.GmailReplyProcessor;
import com.expnai.relay.features.gmail.service.GmailTokenService;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GmailHistorySyncServiceTest {

	@Mock
	private RelayGmailMailboxRepository mailboxRepository;
	@Mock
	private RelayGmailProcessedMessageRepository processedMessageRepository;
	@Mock
	private RelayOutreachMessageRepository outreachMessageRepository;
	@Mock
	private GmailApiClient gmailApiClient;
	@Mock
	private GmailTokenService tokenService;
	@Mock
	private GmailReplyProcessor replyProcessor;
	@Mock
	private PlatformTransactionManager transactionManager;

	private GmailProperties properties;
	private GmailHistorySyncService service;

	@BeforeEach
	void setUp() {
		properties = new GmailProperties();
		properties.setInstanceId("test-instance");
		when(transactionManager.getTransaction(any(TransactionDefinition.class)))
				.thenReturn(new SimpleTransactionStatus());
		service = new GmailHistorySyncService(properties, mailboxRepository, processedMessageRepository,
				outreachMessageRepository, gmailApiClient, tokenService, replyProcessor, transactionManager);
		service.initInstanceId();
		when(mailboxRepository.tryAcquireSyncLease(anyLong(), anyString(), any(), any())).thenReturn(1);
	}

	private RelayGmailMailbox mailbox(long id, String historyId) {
		RelayGmailMailbox m = new RelayGmailMailbox();
		m.setId(id);
		m.setEmail("owner" + id + "@example.com");
		m.setStatus(RelayGmailMailboxStatus.CONNECTED);
		m.setHistoryId(historyId);
		when(mailboxRepository.findById(id)).thenReturn(Optional.of(m));
		when(tokenService.requireAccessToken(m)).thenReturn("token-" + id);
		return m;
	}

	private GmailMessageView view(String id, String thread) {
		return new GmailMessageView(id, thread, "1", "p@x.com", "o@example.com", "Re", "hi", "hi", List.of(), true);
	}

	@Test
	void syncsEachMailboxIndependentlyAndPersistsOwnCursor() {
		RelayGmailMailbox a = mailbox(1, "100");
		RelayGmailMailbox b = mailbox(2, "200");
		when(gmailApiClient.listHistory("token-1", "100", null))
				.thenReturn(new GmailHistoryPage(List.of("m1"), null, "150"));
		when(gmailApiClient.listHistory("token-2", "200", null))
				.thenReturn(new GmailHistoryPage(List.of("m2"), null, "250"));
		when(gmailApiClient.getMessage(anyString(), anyString())).thenAnswer(i -> view(i.getArgument(1), "t"));
		when(replyProcessor.processIncoming(any(), any())).thenReturn("HUMAN_OTHER");

		service.syncMailboxSafe(1L);
		service.syncMailboxSafe(2L);

		assertEquals("150", a.getHistoryId());
		assertEquals("250", b.getHistoryId());
		verify(gmailApiClient, never()).listHistory(eq("token-2"), eq("100"), any());
	}

	@Test
	void restartResumesFromPersistedHistoryId() {
		mailbox(1, "777");
		when(gmailApiClient.listHistory("token-1", "777", null))
				.thenReturn(new GmailHistoryPage(List.of(), null, "777"));

		service.syncMailboxSafe(1L);

		verify(gmailApiClient).listHistory("token-1", "777", null);
		verify(gmailApiClient, never()).getProfile(anyString());
	}

	@Test
	void initialSyncBaselinesWithoutReplayingOldMail() {
		RelayGmailMailbox m = mailbox(1, null);
		when(gmailApiClient.getProfile("token-1")).thenReturn(new GmailApiClient.Profile("o@example.com", "999"));

		service.syncMailboxSafe(1L);

		assertEquals("999", m.getHistoryId());
		verify(gmailApiClient, never()).listHistory(anyString(), anyString(), any());
	}

	@Test
	void expiredCursorRecoversViaThreadSyncAndRebaselines() {
		RelayGmailMailbox m = mailbox(1, "5");
		when(gmailApiClient.listHistory("token-1", "5", null))
				.thenThrow(new GmailApiException("gone", 404, true, false, false, null));
		RelayOutreachMessage sent = new RelayOutreachMessage();
		sent.setGmailThreadId("thread-1");
		when(outreachMessageRepository.findActiveThreadsForMailbox(1L)).thenReturn(List.of(sent));
		when(gmailApiClient.listThreadMessages("token-1", "thread-1")).thenReturn(List.of(view("m9", "thread-1")));
		when(gmailApiClient.getMessage("token-1", "m9")).thenReturn(view("m9", "thread-1"));
		when(replyProcessor.processIncoming(any(), any())).thenReturn("HUMAN_OTHER");
		when(gmailApiClient.getProfile("token-1")).thenReturn(new GmailApiClient.Profile("o@example.com", "9000"));

		service.syncMailboxSafe(1L);

		verify(replyProcessor).processIncoming(eq(m), any());
		assertEquals("9000", m.getHistoryId());
	}

	@Test
	void duplicateMessagesAreProcessedOnce() {
		RelayGmailMailbox m = mailbox(1, "100");
		when(gmailApiClient.listHistory("token-1", "100", null))
				.thenReturn(new GmailHistoryPage(List.of("m1", "m1", "m2"), null, "120"));
		when(processedMessageRepository.existsByMailboxIdAndGmailMessageId(1L, "m2")).thenReturn(true);
		when(gmailApiClient.getMessage("token-1", "m1")).thenReturn(view("m1", "t"));
		when(replyProcessor.processIncoming(any(), any())).thenReturn("HUMAN_OTHER");

		service.syncMailboxSafe(1L);

		verify(replyProcessor, times(1)).processIncoming(eq(m), any());
		verify(gmailApiClient, never()).getMessage("token-1", "m2");
	}

	@Test
	void concurrentInsertOfProcessedRowIsIdempotent() {
		RelayGmailMailbox m = mailbox(1, "100");
		when(gmailApiClient.listHistory("token-1", "100", null))
				.thenReturn(new GmailHistoryPage(List.of("m1"), null, "110"));
		when(gmailApiClient.getMessage("token-1", "m1")).thenReturn(view("m1", "t"));
		when(replyProcessor.processIncoming(any(), any())).thenReturn("HUMAN_OTHER");
		when(processedMessageRepository.saveAndFlush(any(RelayGmailProcessedMessage.class)))
				.thenThrow(new DataIntegrityViolationException("dup"));

		service.syncMailboxSafe(1L);

		assertEquals("110", m.getHistoryId());
	}

	@Test
	void paginatesThroughAllHistoryPages() {
		RelayGmailMailbox m = mailbox(1, "100");
		when(gmailApiClient.listHistory("token-1", "100", null))
				.thenReturn(new GmailHistoryPage(List.of("m1"), "p2", "110"));
		when(gmailApiClient.listHistory("token-1", "100", "p2"))
				.thenReturn(new GmailHistoryPage(List.of("m2"), null, "120"));
		when(gmailApiClient.getMessage(anyString(), anyString())).thenAnswer(i -> view(i.getArgument(1), "t"));
		when(replyProcessor.processIncoming(any(), any())).thenReturn("HUMAN_OTHER");

		service.syncMailboxSafe(1L);

		verify(replyProcessor, times(2)).processIncoming(eq(m), any());
		assertEquals("120", m.getHistoryId());
	}

	@Test
	void failureMidwayDoesNotAdvanceHistoryId() {
		RelayGmailMailbox m = mailbox(1, "100");
		when(gmailApiClient.listHistory("token-1", "100", null))
				.thenReturn(new GmailHistoryPage(List.of("m1"), null, "110"));
		when(gmailApiClient.getMessage("token-1", "m1"))
				.thenThrow(new GmailApiException("boom", 500, false, false, false, null));

		assertThrows(GmailApiException.class, () -> service.syncMailboxSafe(1L));

		assertEquals("100", m.getHistoryId());
		verify(mailboxRepository).releaseSyncLease(1L, "test-instance");
	}

	@Test
	void revokedAuthMarksReauthRequired() {
		RelayGmailMailbox m = mailbox(1, "100");
		when(tokenService.requireAccessToken(m)).thenThrow(new GmailReauthRequiredException("revoked"));

		service.syncMailboxSafe(1L);

		assertEquals(RelayGmailMailboxStatus.REAUTH_REQUIRED, m.getStatus());
		verify(gmailApiClient, never()).listHistory(anyString(), anyString(), any());
	}

	@Test
	void skipsMailboxWhenLeaseHeldByAnotherInstance() {
		mailbox(1, "100");
		when(mailboxRepository.tryAcquireSyncLease(anyLong(), anyString(), any(), any())).thenReturn(0);

		service.syncMailboxSafe(1L);

		verify(gmailApiClient, never()).listHistory(anyString(), anyString(), isNull());
		verify(mailboxRepository, never()).releaseSyncLease(anyLong(), anyString());
	}
}
