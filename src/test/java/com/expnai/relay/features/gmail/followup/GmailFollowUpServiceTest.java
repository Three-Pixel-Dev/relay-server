package com.expnai.relay.features.gmail.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.expnai.relay.domain.entity.RelayOutreachMessage;
import com.expnai.relay.domain.entity.RelayProspect;
import com.expnai.relay.domain.entity.RelayUser;
import com.expnai.relay.domain.enums.RelayProspectStatus;
import com.expnai.relay.domain.repository.RelayOutreachMessageRepository;
import com.expnai.relay.domain.repository.RelaySuppressionRepository;

@ExtendWith(MockitoExtension.class)
class GmailFollowUpServiceTest {

	@Mock
	private RelayOutreachMessageRepository outreachMessageRepository;
	@Mock
	private RelaySuppressionRepository suppressionRepository;
	@InjectMocks
	private GmailFollowUpService followUpService;

	@Test
	void cancelPendingMarksCancelled() {
		RelayOutreachMessage pending = new RelayOutreachMessage();
		pending.setSendStatus("PENDING");
		when(outreachMessageRepository.findPendingFollowUpsForProspect(9L)).thenReturn(List.of(pending));

		followUpService.cancelPendingForProspect(9L, "HUMAN_REPLY");

		ArgumentCaptor<RelayOutreachMessage> captor = ArgumentCaptor.forClass(RelayOutreachMessage.class);
		verify(outreachMessageRepository).save(captor.capture());
		assertEquals("CANCELLED", captor.getValue().getSendStatus());
		assertEquals("HUMAN_REPLY", captor.getValue().getCancelReason());
	}

	@Test
	void canSendFalseWhenAlreadyReplied() {
		RelayUser user = new RelayUser();
		user.setId(1L);
		RelayProspect prospect = new RelayProspect();
		prospect.setUser(user);
		prospect.setEmail("a@b.com");
		prospect.setStatus(RelayProspectStatus.INTERESTED);
		RelayOutreachMessage message = new RelayOutreachMessage();
		message.setSendStatus("PENDING");
		message.setProspect(prospect);

		assertFalse(followUpService.canSendFollowUp(message));
	}
}
