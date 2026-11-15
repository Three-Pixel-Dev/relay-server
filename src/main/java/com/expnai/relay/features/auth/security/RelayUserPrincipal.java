package com.expnai.relay.features.auth.security;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.expnai.relay.domain.entity.RelayUser;
import com.expnai.relay.domain.enums.RelayUserRole;

import lombok.Getter;

@Getter
public class RelayUserPrincipal implements UserDetails {

	private final Long id;
	private final String email;
	private final String passwordHash;
	private final RelayUserRole role;
	private final long tokenVersion;
	private final Long sessionId;
	private final boolean enabled;

	public RelayUserPrincipal(RelayUser user) {
		this(user, null);
	}

	public RelayUserPrincipal(RelayUser user, Long sessionId) {
		this.id = user.getId();
		this.email = user.getEmail();
		this.passwordHash = user.getPasswordHash();
		this.role = user.getRole();
		this.tokenVersion = user.getTokenVersion();
		this.sessionId = sessionId;
		this.enabled = user.isEnable();
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		String roleName = role != null ? role.name() : RelayUserRole.USER.name();
		return List.of(new SimpleGrantedAuthority("ROLE_" + roleName));
	}

	@Override
	public String getPassword() {
		return passwordHash;
	}

	@Override
	public String getUsername() {
		return email;
	}

	@Override
	public boolean isAccountNonExpired() {
		return true;
	}

	@Override
	public boolean isAccountNonLocked() {
		return true;
	}

	@Override
	public boolean isCredentialsNonExpired() {
		return true;
	}

	@Override
	public boolean isEnabled() {
		return enabled;
	}
}
