package com.expnai.relay.common.security;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@Component
public class RelayJwtService {

	private static final String TOKEN_VERSION_CLAIM = "tv";
	private static final String SESSION_ID_CLAIM = "sid";
	private static final String TOKEN_TYPE_CLAIM = "type";
	private static final String REFRESH_TYPE = "refresh";

	@Value("${jwt.secret}")
	private String secret;

	@Value("${jwt.expiration-ms:86400000}")
	private long expirationMs;

	@Value("${jwt.refresh-expiration-ms:604800000}")
	private long refreshExpirationMs;

	public String extractUsername(String token) {
		return extractClaim(token, Claims::getSubject);
	}

	public Long extractTokenVersion(String token) {
		return extractLongClaim(token, TOKEN_VERSION_CLAIM);
	}

	public Long extractSessionId(String token) {
		return extractLongClaim(token, SESSION_ID_CLAIM);
	}

	public boolean isRefreshToken(String token) {
		String type = extractClaim(token, claims -> {
			Object value = claims.get(TOKEN_TYPE_CLAIM);
			return value == null ? null : String.valueOf(value);
		});
		return REFRESH_TYPE.equals(type);
	}

	public String generateAccessToken(UserDetails userDetails, long tokenVersion, long sessionId) {
		Map<String, Object> claims = new HashMap<>();
		claims.put(TOKEN_VERSION_CLAIM, tokenVersion);
		claims.put(SESSION_ID_CLAIM, sessionId);
		return createToken(claims, userDetails.getUsername(), expirationMs);
	}

	public String generateRefreshToken(UserDetails userDetails, long tokenVersion, long sessionId) {
		Map<String, Object> claims = new HashMap<>();
		claims.put(TOKEN_TYPE_CLAIM, REFRESH_TYPE);
		claims.put(TOKEN_VERSION_CLAIM, tokenVersion);
		claims.put(SESSION_ID_CLAIM, sessionId);
		return createToken(claims, userDetails.getUsername(), refreshExpirationMs);
	}

	public boolean validateToken(String token, UserDetails userDetails) {
		String username = extractUsername(token);
		return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
	}

	public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
		return claimsResolver.apply(extractAllClaims(token));
	}

	private Long extractLongClaim(String token, String claim) {
		return extractClaim(token, claims -> {
			Object value = claims.get(claim);
			if (value == null) {
				return null;
			}
			if (value instanceof Integer i) {
				return i.longValue();
			}
			if (value instanceof Long l) {
				return l;
			}
			if (value instanceof String s && !s.isBlank()) {
				return Long.parseLong(s);
			}
			return null;
		});
	}

	private boolean isTokenExpired(String token) {
		return extractClaim(token, Claims::getExpiration).before(new Date());
	}

	private Claims extractAllClaims(String token) {
		return Jwts.parser()
				.verifyWith(signingKey())
				.build()
				.parseSignedClaims(token)
				.getPayload();
	}

	private String createToken(Map<String, Object> claims, String subject, long expirationTime) {
		Date now = new Date();
		return Jwts.builder()
				.claims(claims)
				.subject(subject)
				.issuedAt(now)
				.expiration(new Date(now.getTime() + expirationTime))
				.signWith(signingKey())
				.compact();
	}

	private SecretKey signingKey() {
		return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
	}
}
