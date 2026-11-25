package com.expnai.relay.domain.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.expnai.relay.common.persistence.BaseRepository;
import com.expnai.relay.domain.entity.RelayPromoAsset;
import com.expnai.relay.domain.enums.RelayPromoAssetStatus;
import com.expnai.relay.domain.enums.RelayPromoAssetType;

@Repository
public interface RelayPromoAssetRepository extends BaseRepository<RelayPromoAsset> {

	List<RelayPromoAsset> findByProductIdAndAssetTypeOrderByIdAsc(Long productId, RelayPromoAssetType assetType);

	@Query("""
			select a from RelayPromoAsset a
			where a.status = :status
			  and a.scheduledFor is not null
			  and a.scheduledFor <= :now
			  and a.postedAt is null
			  and a.url is not null
			order by a.scheduledFor asc, a.id asc
			""")
	List<RelayPromoAsset> findDueScheduledPosts(
			@Param("status") RelayPromoAssetStatus status,
			@Param("now") Instant now);
}