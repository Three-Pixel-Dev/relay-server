package com.expnai.relay.domain.entity;

import java.math.BigDecimal;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.expnai.relay.common.persistence.MasterEntity;
import com.expnai.relay.domain.enums.RelayBuildMode;
import com.expnai.relay.domain.enums.RelayProductStage;
import com.expnai.relay.domain.enums.RelayProductType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "relay_product")
public class RelayProduct extends MasterEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private RelayUser user;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "partner_id")
	private RelayPartnership partner;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "chosen_pocket_id")
	private RelayPocket chosenPocket;

	@Column(name = "name", length = 255)
	private String name;

	@Column(name = "auto_named", nullable = false)
	private boolean autoNamed = false;

	@Column(name = "input", columnDefinition = "text")
	private String input;

	@Column(name = "mode", nullable = false, length = 20)
	private RelayBuildMode mode = RelayBuildMode.GUIDED;

	@Column(name = "for_else", nullable = false)
	private boolean forElse = false;

	@Column(name = "client_name", length = 255)
	private String clientName;

	@Column(name = "product_type", length = 30)
	private RelayProductType productType;

	@Column(name = "stage", nullable = false, length = 20)
	private RelayProductStage stage = RelayProductStage.BUILD;

	@Column(name = "build_step", nullable = false)
	private int buildStep = 0;

	@Column(name = "word_count")
	private Integer wordCount;

	@Column(name = "spend", precision = 12, scale = 6)
	private BigDecimal spend = BigDecimal.ZERO;

	@Column(name = "step_status", nullable = false, length = 20)
	private String stepStatus = "todo";

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "step_progress", columnDefinition = "jsonb")
	private String stepProgress;

	@Column(name = "step_note", columnDefinition = "text")
	private String stepNote;

	@Column(name = "quality_warning", columnDefinition = "text")
	private String qualityWarning;

	@Column(name = "quality_override", nullable = false)
	private boolean qualityOverride = false;

	@Column(name = "voice_approved", nullable = false)
	private boolean voiceApproved = false;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "type_price_suggestions_json", columnDefinition = "jsonb")
	private String typePriceSuggestionsJson;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "market_research_json", columnDefinition = "jsonb")
	private String marketResearchJson;

	@Column(name = "cancelled", nullable = false)
	private boolean cancelled = false;
}
