package com.expnai.relay.features.partners.service;

import java.util.List;

import com.expnai.relay.common.web.ApiResponse;
import com.expnai.relay.features.partners.dto.request.RelayAddFromScoutRequest;
import com.expnai.relay.features.partners.dto.request.RelayBuildForPartnerRequest;
import com.expnai.relay.features.partners.dto.request.RelayOwnListRequest;
import com.expnai.relay.features.partners.dto.request.RelayScoutRequest;
import com.expnai.relay.features.partners.dto.request.RelayUpdateOutreachRequest;
import com.expnai.relay.features.partners.dto.request.RelayUpdatePartnerStatusRequest;
import com.expnai.relay.features.partners.dto.response.RelayPartnerResponse;
import com.expnai.relay.features.partners.dto.response.RelayPartnersOverviewResponse;
import com.expnai.relay.features.partners.dto.response.RelayScoutResultResponse;
import com.expnai.relay.features.partners.dto.response.RelaySendOutreachResponse;
import com.expnai.relay.features.product.dto.response.RelayProductResponse;

public interface RelayPartnersService {

	ApiResponse<RelayPartnersOverviewResponse> overview();

	ApiResponse<List<RelayScoutResultResponse>> scout(RelayScoutRequest request);

	ApiResponse<List<RelayPartnerResponse>> addFromScout(RelayAddFromScoutRequest request);

	ApiResponse<List<RelayPartnerResponse>> addOwnList(RelayOwnListRequest request);

	ApiResponse<List<RelayPartnerResponse>> remove(Long partnerId);

	ApiResponse<RelayPartnersOverviewResponse> updateOutreach(RelayUpdateOutreachRequest request);

	ApiResponse<RelaySendOutreachResponse> sendOutreach();

	ApiResponse<RelayPartnerResponse> updateStatus(Long partnerId, RelayUpdatePartnerStatusRequest request);

	ApiResponse<RelayProductResponse> buildForPartner(Long partnerId, RelayBuildForPartnerRequest request);
}
