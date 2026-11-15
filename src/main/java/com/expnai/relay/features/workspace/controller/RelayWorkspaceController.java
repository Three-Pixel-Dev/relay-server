package com.expnai.relay.features.workspace.controller;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.expnai.relay.common.exception.BusinessRuleException;
import com.expnai.relay.common.exception.InvalidSessionException;
import com.expnai.relay.common.exception.ResourceNotFoundException;
import com.expnai.relay.common.web.ApiResponse;
import com.expnai.relay.domain.entity.RelayServiceConnection;
import com.expnai.relay.domain.entity.RelaySubscription;
import com.expnai.relay.domain.entity.RelayUser;
import com.expnai.relay.domain.enums.RelayServiceStatus;
import com.expnai.relay.domain.enums.RelaySubscriptionPlan;
import com.expnai.relay.domain.enums.RelaySubscriptionStatus;
import com.expnai.relay.domain.enums.RelayUserRole;
import com.expnai.relay.domain.repository.RelayServiceConnectionRepository;
import com.expnai.relay.domain.repository.RelaySubscriptionRepository;
import com.expnai.relay.domain.repository.RelayUserRepository;
import com.expnai.relay.features.auth.security.RelayUserPrincipal;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/workspace")
@RequiredArgsConstructor
@Tag(name = "Workspace")
public class RelayWorkspaceController {

	private final RelayUserRepository userRepository;
	private final RelaySubscriptionRepository subscriptionRepository;
	private final RelayServiceConnectionRepository serviceConnectionRepository;

	@GetMapping
	@Transactional(readOnly = true)
	public ApiResponse<Map<String, Object>> getWorkspace() {
		RelayUser user = requireCurrentUser();
		return ApiResponse.ok("Workspace loaded", buildWorkspaceMap(user));
	}

	@PostMapping("/onboard")
	@Transactional
	public ApiResponse<Map<String, Object>> markOnboarded() {
		RelayUser user = requireCurrentUser();
		user.setOnboardSeen(true);
		userRepository.save(user);
		return ApiResponse.ok("Onboard marked", buildWorkspaceMap(user));
	}

	@PostMapping("/subscription")
	@Transactional
	public ApiResponse<Map<String, Object>> startSubscription(
			@RequestBody(required = false) Map<String, String> body) {
		RelayUser user = requireCurrentUser();
		String planStr = body != null ? body.get("plan") : "monthly";
		RelaySubscriptionPlan plan = "yearly".equalsIgnoreCase(planStr)
				? RelaySubscriptionPlan.YEARLY
				: RelaySubscriptionPlan.SIX_MONTHS;

		RelaySubscription sub = new RelaySubscription();
		sub.setUser(user);
		sub.setPlan(plan);
		sub.setStatus(RelaySubscriptionStatus.ACTIVE);
		sub.setCurrentPeriodStart(Instant.now());
		sub.setCurrentPeriodEnd(Instant.now().plus(plan == RelaySubscriptionPlan.YEARLY ? 365 : 180, ChronoUnit.DAYS));
		subscriptionRepository.save(sub);

		return ApiResponse.ok("Subscription started", buildWorkspaceMap(user));
	}

	@PostMapping("/services/{id}/connect")
	@Transactional
	public ApiResponse<Map<String, Object>> connectService(
			@PathVariable("id") String serviceId,
			@RequestBody(required = false) Map<String, String> body) {
		RelayUser user = requireCurrentUser();
		String label = body != null && body.get("label") != null ? body.get("label") : "Connected";
		String credential = body != null ? body.get("credential") : null;

		RelayServiceConnection conn = serviceConnectionRepository
				.findFirstByUserIdAndServiceIdIgnoreCaseOrderByCreatedAtDesc(user.getId(), serviceId)
				.orElseGet(() -> {
					RelayServiceConnection c = new RelayServiceConnection();
					c.setUser(user);
					c.setServiceId(serviceId);
					return c;
				});

		conn.setLabel(label);
		conn.setStatus(RelayServiceStatus.CONNECTED);
		if (credential != null && !credential.isBlank()) {
			String trimmed = credential.trim();
			if (("llm".equalsIgnoreCase(serviceId) || "gemini".equalsIgnoreCase(serviceId))
					&& trimmed.startsWith("sk-")) {
				throw new BusinessRuleException(
						"Text model requires a Google Gemini API key (starts with AIza…) for Gemini embeddings and source summary, not an OpenAI sk-… key.");
			}
			conn.setEncryptedCredential(trimmed);
		}
		serviceConnectionRepository.save(conn);

		return ApiResponse.ok("Service connected", buildWorkspaceMap(user));
	}

	@DeleteMapping("/services/{id}")
	@Transactional
	public ApiResponse<Map<String, Object>> disconnectService(@PathVariable("id") String serviceId) {
		RelayUser user = requireCurrentUser();
		serviceConnectionRepository
				.findFirstByUserIdAndServiceIdIgnoreCaseOrderByCreatedAtDesc(user.getId(), serviceId)
				.ifPresent(conn -> {
					conn.setStatus(RelayServiceStatus.DISCONNECTED);
					conn.setEncryptedCredential(null);
					serviceConnectionRepository.save(conn);
				});

		return ApiResponse.ok("Service disconnected", buildWorkspaceMap(user));
	}

	private RelayUser requireCurrentUser() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || !(auth.getPrincipal() instanceof RelayUserPrincipal principal)) {
			throw new InvalidSessionException("Authentication required.");
		}
		return userRepository.findById(principal.getId())
				.orElseThrow(() -> new ResourceNotFoundException("User not found."));
	}

	private Map<String, Object> buildWorkspaceMap(RelayUser user) {
		Map<String, Object> out = new LinkedHashMap<>();

		RelaySubscription activeSub = subscriptionRepository
				.findFirstByUserIdAndStatusInOrderByCreatedAtDesc(
						user.getId(),
						List.of(RelaySubscriptionStatus.ACTIVE, RelaySubscriptionStatus.TRIALING))
				.orElse(null);

		String subPlan = null;
		if (activeSub != null) {
			subPlan = activeSub.getPlan() == RelaySubscriptionPlan.YEARLY ? "yearly" : "six_months";
		}
		out.put("sub", subPlan);
		out.put("role", user.getRole() == RelayUserRole.ADMIN ? "admin" : "user");
		out.put("seen", Map.of("onboard", user.isOnboardSeen()));

		Map<String, Object> services = new LinkedHashMap<>();
		List<RelayServiceConnection> connections = serviceConnectionRepository.findByUserIdOrderByCreatedAtAsc(user.getId());
		for (RelayServiceConnection conn : connections) {
			if (conn.getStatus() == RelayServiceStatus.CONNECTED) {
				services.put(conn.getServiceId(), Map.of(
						"status", "connected",
						"label", conn.getLabel() != null ? conn.getLabel() : "Connected",
						"t", "Connected"));
			}
		}
		out.put("services", services);
		return out;
	}
}
