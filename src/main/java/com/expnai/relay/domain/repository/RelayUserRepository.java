package com.expnai.relay.domain.repository;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.expnai.relay.common.persistence.BaseRepository;
import com.expnai.relay.domain.entity.RelayUser;

@Repository
public interface RelayUserRepository extends BaseRepository<RelayUser> {

	Optional<RelayUser> findByEmailIgnoreCase(String email);

	boolean existsByEmailIgnoreCase(String email);
}
