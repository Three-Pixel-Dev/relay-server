package com.expnai.relay.features.partners.mapper;

import java.util.Locale;

import org.springframework.stereotype.Component;

import com.expnai.relay.domain.entity.RelayOutreachTemplate;
import com.expnai.relay.domain.entity.RelayPartnership;
import com.expnai.relay.domain.entity.RelayProspect;
import com.expnai.relay.domain.entity.RelayReply;
import com.expnai.relay.domain.enums.RelayProspectOrigin;
import com.expnai.relay.domain.enums.RelayProspectStatus;
import com.expnai.relay.features.partners.dto.response.RelayOutreachTemplateResponse;
import com.expnai.relay.features.partners.dto.response.RelayPartnerResponse;

@Component
public class RelayPartnersMapper {

	public static final String DEFAULT_SUBJECT = "{{first_name}}, your email list is missing one thing";
	public static final String DEFAULT_BODY = """
			Hi {{first_name}},

			I’ve followed your {{niche}} content for a while. You have an audience that trusts you and an email list, and as far as I can see no product of your own to offer them yet.

			I build digital products (guides, workbooks, planners) from a creator’s existing content. I’d write and design one from your videos, you approve every page, and we sell it to your list through one checkout and split the revenue {{split}}. No cost to you and no work beyond approving it.

			Want me to send a one-page outline of what I’d build for you? Reply “yes” and I’ll have it to you in two days.

			{{your_name}}""";
	public static final String DEFAULT_F1 =
			"Hi {{first_name}}, floating this back up. I sketched three product ideas from your last month of content. Want me to send them?";
	public static final String DEFAULT_F2 =
			"Last note from me, {{first_name}}. If the timing is wrong, no problem. If you’d like the outline later, reply any time.";
	public static final int DEFAULT_SPLIT = 50;
	public static final int DEFAULT_CAP = 30;

	public RelayOutreachTemplateResponse toOutreachResponse(RelayOutreachTemplate template) {
		return new RelayOutreachTemplateResponse(
				template.getSubject() != null ? template.getSubject() : DEFAULT_SUBJECT,
				template.getBody() != null ? template.getBody() : DEFAULT_BODY,
				template.getFollowUp1() != null ? template.getFollowUp1() : DEFAULT_F1,
				template.getFollowUp2() != null ? template.getFollowUp2() : DEFAULT_F2,
				template.getSplitPercent() != null ? template.getSplitPercent() : DEFAULT_SPLIT,
				template.getDailyCap() != null ? template.getDailyCap() : DEFAULT_CAP);
	}

	public RelayPartnerResponse toPartnerResponse(
			RelayProspect prospect,
			RelayReply reply,
			RelayPartnership partnership,
			String sentAt,
			int templateSplit) {
		RelayProspectStatus status = prospect.getStatus() != null
				? prospect.getStatus()
				: RelayProspectStatus.QUEUED;
		String replyText = reply != null && reply.getText() != null ? reply.getText() : "";
		String productId = partnership != null && partnership.getProduct() != null
				? String.valueOf(partnership.getProduct().getId())
				: null;
		Integer split = partnership != null
				? partnership.getSplitPercent()
				: templateSplit;
		String from = prospect.getOrigin() == RelayProspectOrigin.OWN ? "own" : "scout";
		String plat = prospect.getPlatform() != null ? prospect.getPlatform().toLowerCase(Locale.ROOT) : "";

		return new RelayPartnerResponse(
				String.valueOf(prospect.getId()),
				nullToEmpty(prospect.getName()),
				nullToEmpty(prospect.getHandle()),
				plat,
				nullToEmpty(prospect.getUrl()),
				nullToEmpty(prospect.getFollowers()),
				nullToEmpty(prospect.getNiche()),
				nullToEmpty(prospect.getListMention()),
				nullToEmpty(prospect.getListSize()),
				nullToEmpty(prospect.getProductNote()),
				nullToEmpty(prospect.getEmail()),
				status.getCode().toLowerCase(Locale.ROOT),
				prospect.getOutreachStep() != null ? prospect.getOutreachStep() : 0,
				replyText,
				productId,
				split,
				from,
				sentAt);
	}

	public String fillTemplate(String template, RelayProspect prospect, int split, String yourName) {
		String text = template != null ? template : "";
		String first = firstName(prospect.getName());
		String niche = prospect.getNiche() != null ? prospect.getNiche() : "your niche";
		String nicheLower = niche.toLowerCase(Locale.ROOT);
		String splitStr = split + "/" + (100 - split);
		String name = yourName != null && !yourName.isBlank() ? yourName : "You";

		text = text.replace("{{first_name}}", first);
		if (prospect.getNiche() == null || prospect.getNiche().isBlank()) {
			text = text.replace("{{niche}} ", "");
		}
		else {
			text = text.replace("{{niche}} ", nicheLower + " ");
		}
		text = text.replace("{{niche}}", nicheLower);
		text = text.replace("{{split}}", splitStr);
		text = text.replace("{{your_name}}", name);
		return text;
	}

	private static String firstName(String name) {
		if (name == null || name.isBlank()) {
			return "there";
		}
		return name.trim().split("\\s+")[0];
	}

	private static String nullToEmpty(String value) {
		return value != null ? value : "";
	}
}
