package com.expnai.relay.domain.repository;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.expnai.relay.common.persistence.BaseRepository;
import com.expnai.relay.domain.entity.RelayWhopProduct;

@Repository
public interface RelayWhopProductRepository extends BaseRepository<RelayWhopProduct> {

	Optional<RelayWhopProduct> findByProductId(Long productId);

	Optional<RelayWhopProduct> findByDeliveryToken(String deliveryToken);

	Optional<RelayWhopProduct> findFirstByExternalProductId(String externalProductId);
}
