package com.expnai.relay.features.partners.mail;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;

import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.expnai.relay.common.exception.BadGatewayException;

import jakarta.mail.Address;
import jakarta.mail.Folder;
import jakarta.mail.Header;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.ReceivedDateTerm;

/** Reads the SMTP mailbox's inbox with the same username and app password used for sending. */
@Component
public class JakartaImapInboxReader implements ImapInboxReader {

	@Value("${relay.mail.imap-host:imap.gmail.com}")
	private String host;

	@Value("${relay.mail.imap-port:993}")
	private int port;

	@Value("${spring.mail.username:}")
	private String username;

	@Value("${spring.mail.password:}")
	private String password;

	@Override
	public List<InboundMail> fetchSince(Instant since, int max) {
		Properties props = new Properties();
		props.put("mail.store.protocol", "imaps");
		props.put("mail.imaps.ssl.enable", "true");
		props.put("mail.imaps.connectiontimeout", "15000");
		props.put("mail.imaps.timeout", "20000");
		Session session = Session.getInstance(props);
		try (Store store = session.getStore("imaps")) {
			store.connect(host, port, username, password);
			Folder inbox = store.getFolder("INBOX");
			inbox.open(Folder.READ_ONLY);
			try {
				Message[] found = inbox.search(new ReceivedDateTerm(ComparisonTerm.GE, Date.from(since)));
				int from = Math.max(0, found.length - Math.max(1, max));
				List<InboundMail> mails = new ArrayList<>();
				for (Message message : Arrays.copyOfRange(found, from, found.length)) {
					mails.add(toInbound(message));
				}
				return mails;
			}
			finally {
				if (inbox.isOpen()) {
					inbox.close(false);
				}
			}
		}
		catch (MessagingException | IOException ex) {
			String reason = ex.getMessage() == null ? "" : ex.getMessage().replaceAll("\\s+", " ");
			throw new BadGatewayException("Could not read the mailbox over IMAP: " + ex.getClass().getSimpleName()
					+ (reason.isBlank() ? "" : " - " + reason.substring(0, Math.min(reason.length(), 200))));
		}
	}

	private static InboundMail toInbound(Message message) throws MessagingException, IOException {
		List<String> headerNames = new ArrayList<>();
		Enumeration<Header> headers = message.getAllHeaders();
		while (headers.hasMoreElements()) {
			headerNames.add(headers.nextElement().getName());
		}
		List<String> references = new ArrayList<>();
		addIds(references, message.getHeader("In-Reply-To"));
		addIds(references, message.getHeader("References"));
		Date received = message.getReceivedDate() != null ? message.getReceivedDate() : message.getSentDate();
		String messageId = firstHeader(message, "Message-ID");
		return new InboundMail(
				messageId,
				join(message.getFrom()),
				join(message.getRecipients(Message.RecipientType.TO)),
				message.getSubject(),
				bodyText(message),
				references,
				headerNames,
				received != null ? received.toInstant() : Instant.now());
	}

	private static void addIds(List<String> target, String[] headerValues) {
		if (headerValues == null) {
			return;
		}
		for (String value : headerValues) {
			for (String token : value.trim().split("\\s+")) {
				if (!token.isBlank()) {
					target.add(token);
				}
			}
		}
	}

	private static String firstHeader(Message message, String name) throws MessagingException {
		String[] values = message.getHeader(name);
		return values != null && values.length > 0 ? values[0].trim() : null;
	}

	private static String join(Address[] addresses) {
		if (addresses == null) {
			return "";
		}
		List<String> parts = new ArrayList<>();
		for (Address address : addresses) {
			parts.add(address.toString());
		}
		return String.join(", ", parts);
	}

	private static String bodyText(Part part) throws MessagingException, IOException {
		if (part.isMimeType("text/plain")) {
			return String.valueOf(part.getContent());
		}
		if (part.isMimeType("text/html")) {
			return Jsoup.parse(String.valueOf(part.getContent())).text();
		}
		if (part.isMimeType("multipart/*")) {
			Multipart multipart = (Multipart) part.getContent();
			String html = "";
			for (int i = 0; i < multipart.getCount(); i++) {
				Part child = multipart.getBodyPart(i);
				if (child.isMimeType("text/plain")) {
					return String.valueOf(child.getContent());
				}
				if (child.isMimeType("text/html") || child.isMimeType("multipart/*")) {
					String nested = bodyText(child);
					if (html.isBlank()) {
						html = nested;
					}
				}
			}
			return html;
		}
		return "";
	}
}
