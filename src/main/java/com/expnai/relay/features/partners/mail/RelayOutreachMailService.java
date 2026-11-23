package com.expnai.relay.features.partners.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.expnai.relay.common.exception.BadGatewayException;
import com.expnai.relay.common.exception.BusinessRuleException;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RelayOutreachMailService {

	private static final Logger log = LoggerFactory.getLogger(RelayOutreachMailService.class);

	private final JavaMailSender mailSender;

	@Value("${spring.mail.username:}")
	private String mailUsername;

	@Value("${spring.mail.password:}")
	private String mailPassword;

	@Value("${relay.mail.from:}")
	private String mailFrom;

	public boolean isConfigured() {
		return StringUtils.hasText(mailUsername) && StringUtils.hasText(mailPassword);
	}

	public String fromAddress() {
		if (StringUtils.hasText(mailFrom)) {
			return mailFrom.trim();
		}
		return mailUsername != null ? mailUsername.trim() : "";
	}

	/** Sends one message and returns its Message-ID header (used later to match the creator's reply). */
	public String send(String to, String subject, String body) {
		if (!isConfigured()) {
			throw new BusinessRuleException(
					"Mailbox SMTP is not configured. Set SPRING_MAIL_USERNAME and SPRING_MAIL_PASSWORD.");
		}
		String from = fromAddress();
		if (!StringUtils.hasText(from)) {
			throw new BusinessRuleException("Mailbox From address is empty. Set RELAY_MAIL_FROM or SPRING_MAIL_USERNAME.");
		}
		try {
			MimeMessage message = mailSender.createMimeMessage();
			MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
			helper.setFrom(from);
			helper.setTo(to);
			helper.setSubject(subject);
			String withUnsub = body
					+ "\n\n—\nReply STOP to unsubscribe. Sent by Relay on behalf of "
					+ from
					+ ".";
			helper.setText(withUnsub, false);
			message.setHeader("List-Unsubscribe", "<mailto:" + from + "?subject=unsubscribe>");
			mailSender.send(message);
			log.info("Outreach email sent to recipient domain={}", domainOf(to));
			return message.getMessageID();
		}
		catch (MailException | jakarta.mail.MessagingException ex) {
			log.warn("Outreach email failed for domain={}: {}", domainOf(to), ex.getClass().getSimpleName());
			throw new BadGatewayException("Failed to send outreach email via SMTP.");
		}
	}

	private static String domainOf(String email) {
		if (email == null) {
			return "";
		}
		int at = email.lastIndexOf('@');
		return at >= 0 ? email.substring(at + 1) : "";
	}
}
