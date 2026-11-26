package com.expnai.relay.features.auth.mail;

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
public class RelayAuthMailService {

	private static final Logger log = LoggerFactory.getLogger(RelayAuthMailService.class);

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

	/** Sends a password-reset email (no Partners unsubscribe footer). */
	public void sendPasswordReset(String to, String resetUrl) {
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
			helper.setSubject("Reset your Relay password");
			helper.setText(buildBody(resetUrl), false);
			mailSender.send(message);
			log.info("Password reset email sent to recipient domain={}", domainOf(to));
		}
		catch (MailException | jakarta.mail.MessagingException ex) {
			log.warn("Password reset email failed for domain={}: {}", domainOf(to), ex.getClass().getSimpleName());
			throw new BadGatewayException("Failed to send password reset email.");
		}
	}

	private static String buildBody(String resetUrl) {
		return """
				Reset your Relay password

				We received a request to reset the password for your Relay account.
				Open this link to choose a new password (expires in 1 hour):

				%s

				If you did not request this, you can ignore this email.
				""".formatted(resetUrl).stripTrailing() + "\n";
	}

	private static String domainOf(String email) {
		if (email == null) {
			return "";
		}
		int at = email.lastIndexOf('@');
		return at >= 0 ? email.substring(at + 1) : "";
	}
}
