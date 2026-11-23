package com.expnai.relay.features.partners.serviceImpl;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.expnai.relay.common.exception.BusinessRuleException;
import com.expnai.relay.common.exception.InvalidSessionException;
import com.expnai.relay.common.exception.ResourceNotFoundException;
import com.expnai.relay.common.web.ApiResponse;
import com.expnai.relay.domain.entity.RelayGmailMailbox;
import com.expnai.relay.domain.entity.RelayOutreachMessage;
import com.expnai.relay.domain.entity.RelayOutreachTemplate;
import com.expnai.relay.domain.entity.RelayPartnership;
import com.expnai.relay.domain.entity.RelayProduct;
import com.expnai.relay.domain.entity.RelayProspect;
import com.expnai.relay.domain.entity.RelayReply;
import com.expnai.relay.domain.entity.RelaySource;
import com.expnai.relay.domain.entity.RelaySubscription;
import com.expnai.relay.domain.entity.RelayThreadMessage;
import com.expnai.relay.domain.entity.RelayUser;
import com.expnai.relay.domain.enums.RelayBuildMode;
import com.expnai.relay.domain.enums.RelayGmailMailboxStatus;
import com.expnai.relay.domain.enums.RelayProductStage;
import com.expnai.relay.domain.enums.RelayProspectOrigin;
import com.expnai.relay.domain.enums.RelayProspectStatus;
import com.expnai.relay.domain.enums.RelayReplyLabel;
import com.expnai.relay.domain.enums.RelaySourceKind;
import com.expnai.relay.domain.enums.RelaySourceStatus;
import com.expnai.relay.domain.enums.RelaySubscriptionStatus;
import com.expnai.relay.domain.enums.RelayThreadWho;
import com.expnai.relay.domain.repository.RelayGmailMailboxRepository;
import com.expnai.relay.domain.repository.RelayOutreachMessageRepository;
import com.expnai.relay.domain.repository.RelayOutreachTemplateRepository;
import com.expnai.relay.domain.repository.RelayPartnershipRepository;
import com.expnai.relay.domain.repository.RelayProductRepository;
import com.expnai.relay.domain.repository.RelayProspectRepository;
import com.expnai.relay.domain.repository.RelayReplyRepository;
import com.expnai.relay.domain.repository.RelaySourceRepository;
import com.expnai.relay.domain.repository.RelaySubscriptionRepository;
import com.expnai.relay.domain.repository.RelaySuppressionRepository;
import com.expnai.relay.domain.repository.RelayThreadMessageRepository;
import com.expnai.relay.domain.repository.RelayUserRepository;
import com.expnai.relay.features.gmail.service.GmailSendService;
import com.expnai.relay.features.auth.security.RelayUserPrincipal;
import com.expnai.relay.features.partners.dto.request.RelayAddFromScoutRequest;
import com.expnai.relay.features.partners.dto.request.RelayBuildForPartnerRequest;
import com.expnai.relay.features.partners.dto.request.RelayOwnListRequest;
import com.expnai.relay.features.partners.dto.request.RelayScoutCreatorRequest;
import com.expnai.relay.features.partners.dto.request.RelayScoutRequest;
import com.expnai.relay.features.partners.dto.request.RelayUpdateOutreachRequest;
import com.expnai.relay.features.partners.dto.request.RelayUpdatePartnerStatusRequest;
import com.expnai.relay.features.partners.dto.response.RelayOutreachTemplateResponse;
import com.expnai.relay.features.partners.dto.response.RelayPartnerResponse;
import com.expnai.relay.features.partners.dto.response.RelayPartnersOverviewResponse;
import com.expnai.relay.features.partners.dto.response.RelayScoutResultResponse;
import com.expnai.relay.features.partners.dto.response.RelaySendOutreachResponse;
import com.expnai.relay.features.partners.mail.RelayOutreachMailService;
import com.expnai.relay.features.partners.mapper.RelayPartnersMapper;
import com.expnai.relay.features.partners.scout.RelayCreatorScoutService;
import com.expnai.relay.features.partners.service.RelayPartnersService;
import com.expnai.relay.features.product.dto.response.RelayProductResponse;
import com.expnai.relay.features.product.mapper.RelayProductMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RelayPartnersServiceImpl implements RelayPartnersService {

	private static final Pattern EMAIL_PATTERN = Pattern.compile("[^\\s,]+@[^\\s,]+\\.[^\\s,]+");

	private final RelayUserRepository userRepository;
	private final RelaySubscriptionRepository subscriptionRepository;
	private final RelayProspectRepository prospectRepository;
	private final RelayOutreachTemplateRepository outreachTemplateRepository;
	private final RelayOutreachMessageRepository outreachMessageRepository;
	private final RelayReplyRepository replyRepository;
	private final RelayPartnershipRepository partnershipRepository;
	private final RelaySuppressionRepository suppressionRepository;
	private final RelayProductRepository productRepository;
	private final RelaySourceRepository sourceRepository;
	private final RelayThreadMessageRepository threadMessageRepository;
	private final RelayPartnersMapper partnersMapper;
	private final RelayProductMapper productMapper;
	private final RelayCreatorScoutService creatorScoutService;
	private final RelayOutreachMailService mailService;
	private final RelayGmailMailboxRepository gmailMailboxRepository;
	private final GmailSendService gmailSendService;

	@Override
	@Transactional
	public ApiResponse<RelayPartnersOverviewResponse> overview() {
		RelayUser user = requireCurrentUser();
		return ApiResponse.ok("OK", buildOverview(user));
	}

	@Override
	public ApiResponse<List<RelayScoutResultResponse>> scout(RelayScoutRequest request) {
		RelayUser user = requireCurrentUser();
		requireActiveSubscription(user);

		List<String> skip = prospectRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
				.map(RelayProspect::getHandle)
				.filter(StringUtils::hasText)
				.toList();
		List<String> platforms = request.getPlatforms() != null ? request.getPlatforms() : List.of();
		List<RelayScoutResultResponse> results = creatorScoutService.scout(request.getQuery(), platforms, skip);
		return ApiResponse.ok(results.isEmpty() ? "No new creators for that search." : "Creators found", results);
	}

	@Override
	@Transactional
	public ApiResponse<List<RelayPartnerResponse>> addFromScout(RelayAddFromScoutRequest request) {
		RelayUser user = requireCurrentUser();
		requireActiveSubscription(user);

		int added = 0;
		for (RelayScoutCreatorRequest creator : request.getCreators()) {
			String email = normalizeEmail(creator.getEmail());
			if (!StringUtils.hasText(email)) {
				continue;
			}
			if (prospectRepository.existsByUserIdAndEmailIgnoreCase(user.getId(), email)) {
				continue;
			}
			if (StringUtils.hasText(creator.getHandle())
					&& prospectRepository.existsByUserIdAndHandleIgnoreCase(user.getId(), creator.getHandle().trim())) {
				continue;
			}
			RelayProspect prospect = new RelayProspect();
			prospect.setUser(user);
			prospect.setName(trimTo(creator.getName(), 255));
			prospect.setHandle(trimTo(creator.getHandle(), 255));
			prospect.setPlatform(normalizePlatform(creator.getPlat()));
			prospect.setUrl(trimTo(creator.getUrl(), 2000));
			prospect.setFollowers(trimTo(creator.getFollowers(), 50));
			prospect.setNiche(trimTo(creator.getNiche(), 500));
			prospect.setListMention(trimTo(creator.getSignal(), null));
			prospect.setListSize(trimTo(creator.getList(), 50));
			prospect.setProductNote(trimTo(creator.getProduct(), null));
			prospect.setEmail(email);
			prospect.setOrigin(RelayProspectOrigin.SCOUT);
			prospect.setStatus(RelayProspectStatus.QUEUED);
			prospect.setOutreachStep(0);
			prospectRepository.save(prospect);
			added++;
		}
		return ApiResponse.ok(added + " added", listPartners(user));
	}

	@Override
	@Transactional
	public ApiResponse<List<RelayPartnerResponse>> addOwnList(RelayOwnListRequest request) {
		RelayUser user = requireCurrentUser();
		requireActiveSubscription(user);

		int added = 0;
		for (String line : request.getText().split("\\R")) {
			String trimmed = line.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			String[] parts = trimmed.split(",");
			List<String> tokens = new ArrayList<>();
			for (String part : parts) {
				String t = part.trim();
				if (!t.isEmpty()) {
					tokens.add(t);
				}
			}
			String rawEmail = tokens.stream()
					.filter(t -> EMAIL_PATTERN.matcher(t).matches() && !isSocialLink(t))
					.findFirst()
					.orElse(null);
			if (rawEmail == null) {
				continue;
			}
			final String email = normalizeEmail(rawEmail);
			if (prospectRepository.existsByUserIdAndEmailIgnoreCase(user.getId(), email)) {
				continue;
			}
			String link = tokens.stream().filter(this::isSocialLink).findFirst().orElse("");
			String name = tokens.stream()
					.filter(t -> !t.equalsIgnoreCase(email) && !t.equals(link))
					.findFirst()
					.orElse(email.split("@")[0]);

			RelayProspect prospect = new RelayProspect();
			prospect.setUser(user);
			prospect.setName(trimTo(name, 255));
			prospect.setEmail(email);
			prospect.setUrl(trimTo(link, 2000));
			prospect.setHandle(trimTo(linkName(link), 255));
			prospect.setPlatform(linkKind(link));
			prospect.setOrigin(RelayProspectOrigin.OWN);
			prospect.setStatus(RelayProspectStatus.QUEUED);
			prospect.setOutreachStep(0);
			prospectRepository.save(prospect);
			added++;
		}
		return ApiResponse.ok(added > 0 ? added + " added from your list." : "No valid emails found.", listPartners(user));
	}

	@Override
	@Transactional
	public ApiResponse<List<RelayPartnerResponse>> remove(Long partnerId) {
		RelayUser user = requireCurrentUser();
		RelayProspect prospect = requireOwnedProspect(user, partnerId);
		if (prospect.getStatus() != RelayProspectStatus.QUEUED) {
			throw new BusinessRuleException("Only queued creators can be removed.");
		}
		prospect.delete();
		prospectRepository.save(prospect);
		return ApiResponse.ok("Removed", listPartners(user));
	}

	@Override
	@Transactional
	public ApiResponse<RelayPartnersOverviewResponse> updateOutreach(RelayUpdateOutreachRequest request) {
		RelayUser user = requireCurrentUser();
		RelayOutreachTemplate template = requireTemplate(user);
		if (request.getSubject() != null) {
			template.setSubject(request.getSubject());
		}
		if (request.getBody() != null) {
			template.setBody(request.getBody());
		}
		if (request.getF1() != null) {
			template.setFollowUp1(request.getF1());
		}
		if (request.getF2() != null) {
			template.setFollowUp2(request.getF2());
		}
		if (request.getSplit() != null) {
			int split = request.getSplit();
			if (split != 30 && split != 40 && split != 50 && split != 60) {
				throw new BusinessRuleException("Split must be 30, 40, 50, or 60.");
			}
			template.setSplitPercent(split);
		}
		if (request.getCap() != null) {
			template.setDailyCap(request.getCap());
		}
		outreachTemplateRepository.save(template);
		return ApiResponse.ok("Outreach updated", buildOverview(user));
	}

	@Override
	@Transactional
	public ApiResponse<RelaySendOutreachResponse> sendOutreach() {
		RelayUser user = requireCurrentUser();
		requireActiveSubscription(user);
		RelayGmailMailbox gmailMailbox = firstConnectedGmail(user);
		boolean useGmail = gmailMailbox != null;
		if (!useGmail && !mailService.isConfigured()) {
			throw new BusinessRuleException(
					"Connect a Gmail mailbox or set SPRING_MAIL_USERNAME / SPRING_MAIL_PASSWORD.");
		}

		RelayOutreachTemplate template = requireTemplate(user);
		RelayOutreachTemplateResponse outreach = partnersMapper.toOutreachResponse(template);
		int cap = outreach.cap();
		Instant dayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
		long alreadySentToday = outreachMessageRepository.countSentSince(user.getId(), dayStart);
		int remainingCap = Math.max(0, cap - (int) alreadySentToday);
		if (remainingCap == 0) {
			throw new BusinessRuleException("Daily outreach cap reached (" + cap + "). Try again tomorrow.");
		}

		List<RelayProspect> queued = prospectRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
				.filter(p -> p.getStatus() == RelayProspectStatus.QUEUED)
				.toList();
		// Prefer oldest first for send order
		queued = new ArrayList<>(queued);
		queued.sort((a, b) -> {
			Instant ca = a.getCreatedAt() != null ? a.getCreatedAt() : Instant.EPOCH;
			Instant cb = b.getCreatedAt() != null ? b.getCreatedAt() : Instant.EPOCH;
			return ca.compareTo(cb);
		});

		int sent = 0;
		int skippedSuppressed = 0;
		int skippedUndeliverable = 0;
		String displayName = StringUtils.hasText(user.getName()) ? user.getName() : "You";

		for (RelayProspect prospect : queued) {
			if (sent >= remainingCap) {
				break;
			}
			String email = prospect.getEmail();
			if (!StringUtils.hasText(email)) {
				continue;
			}
			if (suppressionRepository.existsByUserIdAndEmailIgnoreCase(user.getId(), email)) {
				skippedSuppressed++;
				continue;
			}
			if (isUndeliverableAddress(email)) {
				skippedUndeliverable++;
				continue;
			}

			String subject = partnersMapper.fillTemplate(outreach.subject(), prospect, outreach.split(), displayName);
			String body = partnersMapper.fillTemplate(outreach.body(), prospect, outreach.split(), displayName);
			String idem = "init-" + prospect.getId() + "-1";
			if (useGmail) {
				RelayOutreachMessage message = gmailSendService.sendInitial(
						gmailMailbox, prospect, subject, body, idem);
				gmailSendService.scheduleFollowUps(
						prospect,
						gmailMailbox,
						partnersMapper.fillTemplate(outreach.f1(), prospect, outreach.split(), displayName),
						partnersMapper.fillTemplate(outreach.f2(), prospect, outreach.split(), displayName));
				if (message.getSentAt() == null) {
					continue;
				}
			}
			else {
				String smtpMessageId = mailService.send(email, subject, body);
				RelayOutreachMessage message = new RelayOutreachMessage();
				message.setProspect(prospect);
				message.setStep(1);
				message.setSentAt(Instant.now());
				message.setSubject(subject);
				message.setBody(body);
				message.setProviderId(StringUtils.hasText(smtpMessageId) ? trimTo(smtpMessageId, 255) : "smtp");
				message.setSendStatus("SENT");
				message.setIdempotencyKey(idem);
				outreachMessageRepository.save(message);
			}

			prospect.setStatus(RelayProspectStatus.SENT);
			prospect.setOutreachStep(1);
			prospectRepository.save(prospect);
			sent++;
		}

		long remainingQueued = prospectRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
				.filter(p -> p.getStatus() == RelayProspectStatus.QUEUED)
				.count();

		return ApiResponse.ok(
				sendSummary(sent, skippedSuppressed, skippedUndeliverable),
				new RelaySendOutreachResponse(sent, skippedSuppressed, (int) remainingQueued, listPartners(user)));
	}

	@Override
	@Transactional
	public ApiResponse<RelayPartnerResponse> updateStatus(Long partnerId, RelayUpdatePartnerStatusRequest request) {
		RelayUser user = requireCurrentUser();
		RelayProspect prospect = requireOwnedProspect(user, partnerId);
		String status = request.getStatus() != null ? request.getStatus().trim().toLowerCase(Locale.ROOT) : "";
		RelayProspectStatus next = switch (status) {
			case "interested" -> RelayProspectStatus.INTERESTED;
			case "declined" -> RelayProspectStatus.DECLINED;
			default -> throw new BusinessRuleException("Status must be interested or declined.");
		};
		if (prospect.getStatus() == RelayProspectStatus.PARTNERED) {
			throw new BusinessRuleException("Partnered creators cannot change outreach status.");
		}
		prospect.setStatus(next);
		prospectRepository.save(prospect);

		RelayReply reply = replyRepository.findFirstByProspectIdOrderByCreatedAtDesc(prospect.getId()).orElse(null);
		if (reply != null) {
			reply.setLabel(next == RelayProspectStatus.INTERESTED
					? RelayReplyLabel.INTERESTED
					: RelayReplyLabel.DECLINED);
			reply.setCorrected(true);
			replyRepository.save(reply);
		}

		RelayOutreachTemplate template = requireTemplate(user);
		int split = partnersMapper.toOutreachResponse(template).split();
		RelayPartnership partnership = partnershipRepository.findByProspectId(prospect.getId()).orElse(null);
		String sentAt = outreachMessageRepository.findFirstByProspectIdOrderBySentAtDesc(prospect.getId())
				.map(m -> m.getSentAt() != null ? m.getSentAt().toString() : null)
				.orElse(null);
		return ApiResponse.ok(
				"Status updated",
				partnersMapper.toPartnerResponse(prospect, reply, partnership, sentAt, split));
	}

	@Override
	@Transactional
	public ApiResponse<RelayProductResponse> buildForPartner(Long partnerId, RelayBuildForPartnerRequest request) {
		RelayUser user = requireCurrentUser();
		requireActiveSubscription(user);
		RelayProspect prospect = requireOwnedProspect(user, partnerId);
		if (prospect.getStatus() != RelayProspectStatus.INTERESTED
				&& prospect.getStatus() != RelayProspectStatus.PARTNERED) {
			throw new BusinessRuleException("Build is available after a creator is marked interested.");
		}
		if (partnershipRepository.findByProspectId(prospect.getId()).isPresent()
				&& prospect.getStatus() == RelayProspectStatus.PARTNERED) {
			RelayPartnership existing = partnershipRepository.findByProspectId(prospect.getId()).orElseThrow();
			if (existing.getProduct() != null) {
				return ApiResponse.ok("Already building", toProductResponse(existing.getProduct()));
			}
		}

		RelayOutreachTemplate template = requireTemplate(user);
		int split = partnersMapper.toOutreachResponse(template).split();

		String niche = StringUtils.hasText(prospect.getNiche()) ? prospect.getNiche().trim() : "";
		String client = StringUtils.hasText(prospect.getName()) ? prospect.getName().trim() : "Creator";
		String productName = StringUtils.hasText(niche) ? niche : (client + "'s product");
		RelayBuildMode mode = parseMode(request != null ? request.getMode() : null);

		RelayProduct product = new RelayProduct();
		product.setUser(user);
		product.setName(trimTo(productName, 255));
		product.setInput(niche);
		product.setMode(mode);
		product.setForElse(true);
		product.setClientName(trimTo(client, 255));
		product.setStage(RelayProductStage.BUILD);
		product.setBuildStep(0);
		product.setStepStatus("todo");
		product.setSpend(BigDecimal.ZERO);
		product = productRepository.save(product);

		List<RelaySource> sources = new ArrayList<>();
		String link = resolveCreatorUrl(prospect);
		if (StringUtils.hasText(link)) {
			RelaySource source = new RelaySource();
			source.setProduct(product);
			source.setKind(resolveSourceKind(prospect.getPlatform(), link));
			source.setName(StringUtils.hasText(prospect.getHandle()) ? prospect.getHandle() : linkName(link));
			source.setUrl(link);
			source.setStatus(RelaySourceStatus.PENDING);
			sources.add(sourceRepository.save(source));
		}

		RelayPartnership partnership = partnershipRepository.findByProspectId(prospect.getId()).orElseGet(() -> {
			RelayPartnership p = new RelayPartnership();
			p.setUser(user);
			p.setProspect(prospect);
			return p;
		});
		partnership.setProduct(product);
		partnership.setSplitPercent(split);
		partnership = partnershipRepository.save(partnership);

		product.setPartner(partnership);
		product = productRepository.save(product);

		prospect.setStatus(RelayProspectStatus.PARTNERED);
		prospectRepository.save(prospect);

		String greeting = "Got it"
				+ (StringUtils.hasText(niche) ? ": **" + niche + "**" : "")
				+ ". Building for **" + client + "**"
				+ (sources.isEmpty() ? "." : " from their content. " + sources.size()
						+ " source" + (sources.size() > 1 ? "s" : "") + " to read first.")
				+ " Agreed split: creator " + split + "%.";
		saveThread(product, greeting);

		return ApiResponse.created("Partner product created", toProductResponse(product));
	}

	private RelayPartnersOverviewResponse buildOverview(RelayUser user) {
		RelayOutreachTemplate template = requireTemplate(user);
		RelayGmailMailbox gmail = firstConnectedGmail(user);
		boolean mailConfigured = gmail != null || mailService.isConfigured();
		String mailFrom = gmail != null
				? gmail.getEmail()
				: (mailService.isConfigured() ? mailService.fromAddress() : "");
		return new RelayPartnersOverviewResponse(
				listPartners(user),
				partnersMapper.toOutreachResponse(template),
				mailConfigured,
				mailFrom,
				gmail != null,
				gmail != null ? gmail.getEmail() : "");
	}

	private RelayGmailMailbox firstConnectedGmail(RelayUser user) {
		return gmailMailboxRepository.findByUserIdOrderByCreatedAtAsc(user.getId()).stream()
				.filter(m -> m.getStatus() == RelayGmailMailboxStatus.CONNECTED)
				.findFirst()
				.orElse(null);
	}

	private List<RelayPartnerResponse> listPartners(RelayUser user) {
		List<RelayProspect> prospects = prospectRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
		if (prospects.isEmpty()) {
			return List.of();
		}
		List<Long> ids = prospects.stream().map(RelayProspect::getId).toList();
		Map<Long, RelayReply> replies = new HashMap<>();
		for (RelayReply reply : replyRepository.findByProspectIdIn(ids)) {
			replies.putIfAbsent(reply.getProspect().getId(), reply);
		}
		Map<Long, RelayPartnership> partnerships = new HashMap<>();
		for (RelayPartnership partnership : partnershipRepository.findByProspectIdIn(ids)) {
			partnerships.put(partnership.getProspect().getId(), partnership);
		}
		int split = partnersMapper.toOutreachResponse(requireTemplate(user)).split();
		List<RelayPartnerResponse> out = new ArrayList<>();
		for (RelayProspect prospect : prospects) {
			String sentAt = outreachMessageRepository.findFirstByProspectIdOrderBySentAtDesc(prospect.getId())
					.map(m -> m.getSentAt() != null ? m.getSentAt().toString() : null)
					.orElse(null);
			out.add(partnersMapper.toPartnerResponse(
					prospect,
					replies.get(prospect.getId()),
					partnerships.get(prospect.getId()),
					sentAt,
					split));
		}
		return out;
	}

	private RelayOutreachTemplate requireTemplate(RelayUser user) {
		return outreachTemplateRepository.findFirstByUserIdOrderByCreatedAtAsc(user.getId())
				.orElseGet(() -> {
					RelayOutreachTemplate template = new RelayOutreachTemplate();
					template.setUser(user);
					template.setSubject(RelayPartnersMapper.DEFAULT_SUBJECT);
					template.setBody(RelayPartnersMapper.DEFAULT_BODY);
					template.setFollowUp1(RelayPartnersMapper.DEFAULT_F1);
					template.setFollowUp2(RelayPartnersMapper.DEFAULT_F2);
					template.setSplitPercent(RelayPartnersMapper.DEFAULT_SPLIT);
					template.setDailyCap(RelayPartnersMapper.DEFAULT_CAP);
					return outreachTemplateRepository.save(template);
				});
	}

	private RelayProspect requireOwnedProspect(RelayUser user, Long partnerId) {
		return prospectRepository.findByIdAndUserId(partnerId, user.getId())
				.orElseThrow(() -> new ResourceNotFoundException("Partner not found."));
	}

	private void requireActiveSubscription(RelayUser user) {
		RelaySubscription sub = subscriptionRepository
				.findFirstByUserIdAndStatusInOrderByCreatedAtDesc(
						user.getId(),
						List.of(RelaySubscriptionStatus.ACTIVE, RelaySubscriptionStatus.TRIALING))
				.orElse(null);
		if (sub == null) {
			throw new BusinessRuleException("Start your plan to find partners.");
		}
	}

	private RelayUser requireCurrentUser() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof RelayUserPrincipal principal)) {
			throw new InvalidSessionException("Not authenticated");
		}
		return userRepository.findById(principal.getId())
				.orElseThrow(() -> new ResourceNotFoundException("User not found."));
	}

	private RelayProductResponse toProductResponse(RelayProduct product) {
		List<RelaySource> sources = sourceRepository.findByProductIdOrderByCreatedAtAsc(product.getId());
		List<RelayThreadMessage> thread = threadMessageRepository.findByProductIdOrderByCreatedAtAscIdAsc(product.getId());
		return productMapper.toDto(product, sources, null, thread);
	}

	private void saveThread(RelayProduct product, String text) {
		RelayThreadMessage msg = new RelayThreadMessage();
		msg.setProduct(product);
		msg.setWho(RelayThreadWho.RELAY);
		msg.setText(text);
		threadMessageRepository.save(msg);
	}

	private static RelayBuildMode parseMode(String mode) {
		if (mode == null || mode.isBlank()) {
			return RelayBuildMode.GUIDED;
		}
		return "fast".equalsIgnoreCase(mode.trim()) ? RelayBuildMode.FAST : RelayBuildMode.GUIDED;
	}

	private static String normalizeEmail(String email) {
		return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
	}

	private static String normalizePlatform(String plat) {
		if (!StringUtils.hasText(plat)) {
			return "";
		}
		String p = plat.trim().toLowerCase(Locale.ROOT);
		if (p.contains("you")) {
			return "youtube";
		}
		if (p.contains("insta")) {
			return "instagram";
		}
		return p;
	}

	private boolean isSocialLink(String value) {
		String v = value.toLowerCase(Locale.ROOT);
		return v.contains("instagram") || v.contains("youtu");
	}

	private String linkKind(String link) {
		if (!StringUtils.hasText(link)) {
			return "";
		}
		String v = link.toLowerCase(Locale.ROOT);
		if (v.contains("youtu")) {
			return "youtube";
		}
		if (v.contains("instagram")) {
			return "instagram";
		}
		return "";
	}

	private String linkName(String link) {
		if (!StringUtils.hasText(link)) {
			return "";
		}
		String cleaned = link.replaceAll("https?://", "").replaceAll("^www\\.", "");
		int slash = cleaned.indexOf('/');
		if (slash >= 0 && slash < cleaned.length() - 1) {
			String path = cleaned.substring(slash + 1).replaceAll("/.*", "");
			if (!path.isBlank()) {
				return path.startsWith("@") ? path : "@" + path;
			}
		}
		return cleaned;
	}

	private String resolveCreatorUrl(RelayProspect prospect) {
		if (StringUtils.hasText(prospect.getUrl())) {
			return prospect.getUrl().trim();
		}
		if (!StringUtils.hasText(prospect.getHandle())) {
			return "";
		}
		String handle = prospect.getHandle().trim();
		String plat = prospect.getPlatform() != null ? prospect.getPlatform().toLowerCase(Locale.ROOT) : "";
		if ("youtube".equals(plat)) {
			return "youtube.com/" + (handle.startsWith("@") ? handle : "@" + handle);
		}
		if ("instagram".equals(plat)) {
			return "instagram.com/" + handle.replace("@", "");
		}
		return "";
	}

	private RelaySourceKind resolveSourceKind(String platform, String url) {
		String plat = platform != null ? platform.toLowerCase(Locale.ROOT) : "";
		String u = url != null ? url.toLowerCase(Locale.ROOT) : "";
		if ("youtube".equals(plat) || u.contains("youtu")) {
			return RelaySourceKind.YOUTUBE;
		}
		if ("instagram".equals(plat) || u.contains("instagram")) {
			return RelaySourceKind.INSTAGRAM;
		}
		return RelaySourceKind.WEB;
	}

	private static String trimTo(String value, Integer max) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		if (trimmed.isEmpty()) {
			return null;
		}
		if (max != null && trimmed.length() > max) {
			return trimmed.substring(0, max);
		}
		return trimmed;
	}
	private static boolean isUndeliverableAddress(String email) {
		String v = email.trim().toLowerCase(Locale.ROOT);
		return v.endsWith(".example") || v.endsWith(".invalid") || v.endsWith(".test") || v.endsWith(".localhost");
	}

	private static String sendSummary(int sent, int skippedSuppressed, int skippedUndeliverable) {
		StringBuilder summary = new StringBuilder(sent + " sent");
		if (skippedSuppressed > 0) {
			summary.append(", ").append(skippedSuppressed).append(" suppressed");
		}
		if (skippedUndeliverable > 0) {
			summary.append(", ").append(skippedUndeliverable).append(" skipped (no real email address)");
		}
		return summary.toString();
	}
}
