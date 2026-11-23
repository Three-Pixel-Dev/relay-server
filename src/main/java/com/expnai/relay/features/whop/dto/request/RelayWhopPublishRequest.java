package com.expnai.relay.features.whop.dto.request;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RelayWhopPublishRequest {

	@NotNull
	@DecimalMin(value = "1.00", message = "Price must be at least 1.00")
	@Schema(description = "One-time price charged at Whop checkout")
	private BigDecimal price;

	@Size(max = 3)
	@Schema(description = "ISO currency code, defaults to USD", example = "USD")
	private String currency;

	@Size(max = 80)
	@Schema(description = "Product title on Whop. Defaults to the cover title.")
	private String title;

	@Size(max = 80)
	@Schema(description = "Short headline shown under the title")
	private String headline;

	@DecimalMin("0")
	@DecimalMax("100")
	@Schema(description = "Percent of each sale paid to Whop affiliates. Omit to leave affiliates off.")
	private BigDecimal affiliatePercent;
}
