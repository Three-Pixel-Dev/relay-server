package com.expnai.relay.features.gmail.followup;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.expnai.relay.domain.entity.RelayOutreachMessage;
import com.expnai.relay.domain.enums.RelayProspectStatus;
import com.expnai.relay.domain.repository.RelayOutreachMessageRepository;
import com.expnai.relay.domain.repository.RelaySuppressionRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class GmailFollowUpService {

	private final RelayOutreachMessageRepository outreachMessageRepository;
	private final RelaySuppressionRepository suppressionRepository;

	@Transactional
	public void cancelPendingForProspect(Long prospectId, String reason) {
		List<RelayOutreachMessage> pending = outreachMessageRepository.findPendingFollowUpsForProspect(prospectId);
		Instant now = Instant.now();
		for (RelayOutreachMessage message : pending) {
			message.setSendStatus("CANCELLED");
			message.setCancelledAt(now);
			message.setCancelReason(reason);
			outreachMessageRepository.save(message);
		}
	}

	/**
	 * Returns true if this follow-up is still allowed to send (rechecked immediately before send).
	 */
	@Transactional(readOnly = true)
	public boolean canSendFollowUp(RelayOutreachMessage message) {
		if (message.getCancelledAt() != null || !"PENDING".equals(message.getSendStatus())) {
			return false;
		}
		var prospect = message.getProspect();
		if (prospect.getStatus() == RelayProspectStatus.INTERESTED
				|| prospect.getStatus() == RelayProspectStatus.DECLINED
				|| prospect.getStatus() == RelayProspectStatus.PARTNERED
				|| prospect.getStatus() == RelayProspectStatus.REPLIED) {
			return false;
		}
		String email = prospect.getEmail();
		if (email != null
				&& suppressionRepository.existsByUserIdAndEmailIgnoreCase(prospect.getUser().getId(), email)) {
			return false;
		}
		return true;
	}
}
