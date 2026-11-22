package com.expnai.relay.features.partners.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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
import com.expnai.relay.features.partners.service.RelayPartnersService;
import com.expnai.relay.features.product.dto.response.RelayProductResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/partners")
@RequiredArgsConstructor
@Tag(name = "Partners")
public class RelayPartnersController {

	private final RelayPartnersService partnersService;

	@GetMapping
	@Operation(summary = "Partners overview: list, outreach template, mail status")
	public ApiResponse<RelayPartnersOverviewResponse> overview() {
		return partnersService.overview();
	}

	@PostMapping("/scout")
	@Operation(summary = "Find creators with a public email-list mention and business email")
	public ApiResponse<List<RelayScoutResultResponse>> scout(@Valid @RequestBody(required = false) RelayScoutRequest request) {
		return partnersService.scout(request != null ? request : new RelayScoutRequest());
	}

	@PostMapping("/from-scout")
	@Operation(summary = "Add selected scout results to the outreach list")
	public ApiResponse<List<RelayPartnerResponse>> addFromScout(@Valid @RequestBody RelayAddFromScoutRequest request) {
		return partnersService.addFromScout(request);
	}

	@PostMapping("/own-list")
	@Operation(summary = "Import own contacts (name, email, optional social link per line)")
	public ApiResponse<List<RelayPartnerResponse>> addOwnList(@Valid @RequestBody RelayOwnListRequest request) {
		return partnersService.addOwnList(request);
	}

	@DeleteMapping("/{id}")
	@Operation(summary = "Remove a queued creator from the list")
	public ApiResponse<List<RelayPartnerResponse>> remove(@PathVariable Long id) {
		return partnersService.remove(id);
	}

	@PutMapping("/outreach")
	@Operation(summary = "Update outreach template, split, and daily cap")
	public ApiResponse<RelayPartnersOverviewResponse> updateOutreach(@Valid @RequestBody RelayUpdateOutreachRequest request) {
		return partnersService.updateOutreach(request);
	}

	@PostMapping("/send")
	@Operation(summary = "Send outreach emails via Spring Mail SMTP (respects daily cap and suppressions)")
	public ApiResponse<RelaySendOutreachResponse> send() {
		return partnersService.sendOutreach();
	}

	@PutMapping("/{id}/status")
	@Operation(summary = "Correct reply label: interested or declined")
	public ApiResponse<RelayPartnerResponse> updateStatus(
			@PathVariable Long id,
			@Valid @RequestBody RelayUpdatePartnerStatusRequest request) {
		return partnersService.updateStatus(id, request);
	}

	@PostMapping("/{id}/build")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Build their product (agency mode) and mark as Partner")
	public ApiResponse<RelayProductResponse> build(
			@PathVariable Long id,
			@RequestBody(required = false) RelayBuildForPartnerRequest request) {
		return partnersService.buildForPartner(id, request != null ? request : new RelayBuildForPartnerRequest());
	}
}
