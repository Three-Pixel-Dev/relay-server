package com.expnai.relay.features.auth.security;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.expnai.relay.common.persistence.AuditUserContext;
import com.expnai.relay.common.security.RelayJwtService;
import com.expnai.relay.domain.repository.RelayUserRepository;
import com.expnai.relay.domain.repository.RelayUserSessionRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RelayJwtAuthenticationFilter extends OncePerRequestFilter {

	private final RelayJwtService jwtService;
	private final RelayUserRepository userRepository;
	private final RelayUserSessionRepository sessionRepository;

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
			filterChain.doFilter(request, response);
			return;
		}

		String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (authHeader == null || !authHeader.startsWith("Bearer ")) {
			filterChain.doFilter(request, response);
			return;
		}

		try {
			String jwt = authHeader.substring(7);
			if (jwtService.isRefreshToken(jwt)) {
				filterChain.doFilter(request, response);
				return;
			}

			String email = jwtService.extractUsername(jwt);
			if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {
				userRepository.findByEmailIgnoreCase(email).ifPresent(user -> {
					RelayUserPrincipal principal = new RelayUserPrincipal(user, jwtService.extractSessionId(jwt));
					if (!jwtService.validateToken(jwt, principal)) {
						return;
					}
					Long tokenVersion = jwtService.extractTokenVersion(jwt);
					if (tokenVersion == null || tokenVersion != principal.getTokenVersion()) {
						return;
					}
					Long sessionId = principal.getSessionId();
					if (sessionId != null
							&& sessionRepository.findByIdAndUserIdAndRevokedAtIsNull(sessionId, user.getId()).isEmpty()) {
						return;
					}

					UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
							principal,
							null,
							principal.getAuthorities());
					authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
					SecurityContextHolder.getContext().setAuthentication(authToken);
					AuditUserContext.setCurrentUserId(principal.getId());
				});
			}
		}
		catch (Exception ignored) {
			// Invalid token: continue without authentication.
		}

		try {
			filterChain.doFilter(request, response);
		}
		finally {
			AuditUserContext.clear();
		}
	}
}
