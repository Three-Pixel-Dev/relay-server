package com.expnai.relay.domain.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.expnai.relay.common.persistence.BaseRepository;
import com.expnai.relay.domain.entity.RelayServiceConnection;
import com.expnai.relay.domain.enums.RelayServiceStatus;

@Repository
public interface RelayServiceConnectionRepository extends BaseRepository<RelayServiceConnection> {

	List<RelayServiceConnection> findByUserIdOrderByCreatedAtAsc(Long userId);

	Optional<RelayServiceConnection> findFirstByUserIdAndServiceIdIgnoreCaseOrderByCreatedAtDesc(
			Long userId,
			String serviceId);

	Optional<RelayServiceConnection> findFirstByUserIdAndServiceIdIgnoreCaseAndStatusOrderByCreatedAtDesc(
			Long userId,
			String serviceId,
			RelayServiceStatus status);

	Optional<RelayServiceConnection> findByWebhookToken(String webhookToken);
}
