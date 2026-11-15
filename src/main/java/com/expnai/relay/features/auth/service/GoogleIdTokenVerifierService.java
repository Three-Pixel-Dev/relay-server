package com.expnai.relay.features.auth.service;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;

/**
 * Verifies Google Sign-In ID tokens against configured OAuth client IDs (audiences).
 * Supports multiple client IDs (Android, iOS, Web) for the same backend.
 */
@Service
public class GoogleIdTokenVerifierService {

	private static final Logger log = LoggerFactory.getLogger(GoogleIdTokenVerifierService.class);

	private final List<String> allowedClientIds;
	private final AtomicReference<GoogleIdTokenVerifier> verifierRef = new AtomicReference<>();

	public GoogleIdTokenVerifierService(@Value("${google.oauth.client-ids:}") String clientIdsCsv) {
		this.allowedClientIds = parseClientIds(clientIdsCsv);
		if (this.allowedClientIds.isEmpty()) {
			log.warn("google.oauth.client-ids is empty — Google Sign-In will reject all tokens until configured");
		}
		else {
			log.info("Google OAuth audiences configured: count={}", this.allowedClientIds.size());
		}
	}

	public VerifiedGoogleIdentity verify(String idTokenRaw) {
		if (idTokenRaw == null || idTokenRaw.isBlank()) {
			throw new BadCredentialsException("Google ID token is required.");
		}
		if (allowedClientIds.isEmpty()) {
			throw new BadCredentialsException("Google Sign-In is not configured on the server.");
		}

		GoogleIdToken idToken;
		try {
			idToken = verifier().verify(idTokenRaw.trim());
		}
		catch (GeneralSecurityException | IOException e) {
			log.warn("Google ID token verification failed: {}", e.getMessage());
			throw new BadCredentialsException("Invalid Google ID token.");
		}
		catch (IllegalArgumentException e) {
			throw new BadCredentialsException("Invalid Google ID token.");
		}

		if (idToken == null) {
			throw new BadCredentialsException("Invalid or expired Google ID token.");
		}

		GoogleIdToken.Payload payload = idToken.getPayload();
		String subject = payload.getSubject();
		if (subject == null || subject.isBlank()) {
			throw new BadCredentialsException("Invalid Google ID token.");
		}

		Boolean emailVerified = payload.getEmailVerified();
		String email = payload.getEmail();
		if (email != null && !email.isBlank() && !Boolean.TRUE.equals(emailVerified)) {
			throw new BadCredentialsException("Google email is not verified.");
		}

		String name = payload.get("name") instanceof String s ? s : null;
		String picture = payload.get("picture") instanceof String s ? s : null;

		return new VerifiedGoogleIdentity(
				subject.trim(),
				email != null ? email.trim().toLowerCase() : null,
				name != null ? name.trim() : null,
				picture);
	}

	private GoogleIdTokenVerifier verifier() {
		GoogleIdTokenVerifier existing = verifierRef.get();
		if (existing != null) {
			return existing;
		}
		GoogleIdTokenVerifier created = new GoogleIdTokenVerifier.Builder(
				new NetHttpTransport(),
				GsonFactory.getDefaultInstance())
				.setAudience(allowedClientIds)
				.build();
		if (verifierRef.compareAndSet(null, created)) {
			return created;
		}
		return verifierRef.get();
	}

	private static List<String> parseClientIds(String csv) {
		if (csv == null || csv.isBlank()) {
			return Collections.emptyList();
		}
		return Arrays.stream(csv.split(","))
				.map(String::trim)
				.filter(s -> !s.isEmpty())
				.distinct()
				.collect(Collectors.toList());
	}

	public record VerifiedGoogleIdentity(
			String googleId,
			String email,
			String displayName,
			String pictureUrl) {
	}
}
