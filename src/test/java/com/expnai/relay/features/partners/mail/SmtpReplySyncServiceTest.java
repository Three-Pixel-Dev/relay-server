package com.expnai.relay.features.partners.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.expnai.relay.domain.entity.RelayOutreachMessage;
import com.expnai.relay.domain.entity.RelayProspect;
import com.expnai.relay.domain.enums.RelayProspectStatus;
import com.expnai.relay.domain.repository.RelayOutreachMessageRepository;
import com.expnai.relay.features.gmail.client.GmailMessageView;
import com.expnai.relay.features.gmail.reply.GmailReplyClassifier;
import com.expnai.relay.features.gmail.reply.GmailReplyProcessor;
import com.expnai.relay.features.gmail.reply.ReplyClassification;

class SmtpReplySyncServiceTest {

	private final RelayOutreachMessageRepository outreachRepo = mock(RelayOutreachMessageRepository.class);
	private final RelayOutreachMailService mailService = mock(RelayOutreachMailService.class);
	private final ImapInboxReader inboxReader = mock(ImapInboxReader.class);
	private final GmailReplyProcessor replyProcessor = mock(GmailReplyProcessor.class);
	private final SmtpReplySyncService service = new SmtpReplySyncService(
			outreachRepo, mailService, inboxReader, new GmailReplyClassifier(), replyProcessor);

	private RelayProspect prospect;
	private final Instant sentAt = Instant.parse("2026-11-23T10:00:00Z");

	@BeforeEach
	void setUp() {
		when(mailService.isConfigured()).thenReturn(true);
		when(mailService.fromAddress()).thenReturn("me@gmail.com");
		prospect = new RelayProspect();
		prospect.setEmail("creator@gmail.com");
		prospect.setStatus(RelayProspectStatus.SENT);
	}

	private RelayOutreachMessage outreach(String providerId) {
		RelayOutreachMessage message = new RelayOutreachMessage();
		message.setProspect(prospect);
		message.setProviderId(providerId);
		message.setSentAt(sentAt);
		return message;
	}

	private InboundMail mail(String from, String body, List<String> references, Instant at) {
		return new InboundMail("<reply-1@gmail.com>", from, "me@gmail.com", "Re: hello", body, references, List.of(), at);
	}

	@Test
	void appliesAReplyThatReferencesTheSentMessageId() {
		when(outreachRepo.findSmtpAwaitingReply()).thenReturn(List.of(outreach("<sent-1@gmail.com>")));
		when(inboxReader.fetchSince(any(), anyInt())).thenReturn(List.of(
				mail("Creator <creator@gmail.com>", "Yes, send it", List.of("<sent-1@gmail.com>"), sentAt.plusSeconds(600))));
		when(replyProcessor.applyClassification(any(), any(), any())).thenReturn("HUMAN_INTERESTED");

		assertEquals(1, service.sync());

		ArgumentCaptor<GmailMessageView> view = ArgumentCaptor.forClass(GmailMessageView.class);
		verify(replyProcessor).applyClassification(eq(prospect), view.capture(), eq(ReplyClassification.HUMAN_INTERESTED));
		assertEquals("Yes, send it", view.getValue().bodyText());
	}

	@Test
	void fallsBackToTheSenderAddressWhenThereIsNoMessageIdMatch() {
		when(outreachRepo.findSmtpAwaitingReply()).thenReturn(List.of(outreach("smtp")));
		when(inboxReader.fetchSince(any(), anyInt())).thenReturn(List.of(
				mail("creator@gmail.com", "hello", List.of(), sentAt.plusSeconds(120))));
		when(replyProcessor.applyClassification(any(), any(), any())).thenReturn("HUMAN_OTHER");

		assertEquals(1, service.sync());
		verify(replyProcessor).applyClassification(eq(prospect), any(), eq(ReplyClassification.HUMAN_OTHER));
	}

	@Test
	void ignoresMessagesFromOthersFromBeforeTheSendAndFromTheMailboxItself() {
		when(outreachRepo.findSmtpAwaitingReply()).thenReturn(List.of(outreach("smtp")));
		when(inboxReader.fetchSince(any(), anyInt())).thenReturn(List.of(
				mail("someone@else.com", "hi", List.of(), sentAt.plusSeconds(60)),
				mail("creator@gmail.com", "old", List.of(), sentAt.minusSeconds(3600)),
				mail("me@gmail.com", "my own copy", List.of(), sentAt.plusSeconds(60))));

		assertEquals(0, service.sync());
		verify(replyProcessor, never()).applyClassification(any(), any(), any());
	}

	@Test
	void doesNothingWithoutSmtpOrWithoutOutreachAwaitingAReply() {
		when(outreachRepo.findSmtpAwaitingReply()).thenReturn(List.of());
		assertEquals(0, service.sync());
		verify(inboxReader, never()).fetchSince(any(), anyInt());

		when(mailService.isConfigured()).thenReturn(false);
		assertEquals(0, service.sync());
	}
}
