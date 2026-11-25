package com.expnai.relay.domain.repository;

import java.util.List;

import org.springframework.stereotype.Repository;

import com.expnai.relay.common.persistence.BaseRepository;
import com.expnai.relay.domain.entity.RelayPromoAsset;
import com.expnai.relay.domain.enums.RelayPromoAssetType;

@Repository
public interface RelayPromoAssetRepository extends BaseRepository<RelayPromoAsset> {

	List<RelayPromoAsset> findByProductIdAndAssetTypeOrderByIdAsc(Long productId, RelayPromoAssetType assetType);
}
