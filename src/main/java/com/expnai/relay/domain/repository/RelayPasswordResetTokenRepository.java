package com.expnai.relay.domain.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.expnai.relay.common.persistence.BaseRepository;
import com.expnai.relay.domain.entity.RelayPasswordResetToken;

@Repository
public interface RelayPasswordResetTokenRepository extends BaseRepository<RelayPasswordResetToken> {

	Optional<RelayPasswordResetToken> findByTokenHashAndUsedAtIsNull(String tokenHash);

	List<RelayPasswordResetToken> findByUserIdAndUsedAtIsNull(Long userId);
}
