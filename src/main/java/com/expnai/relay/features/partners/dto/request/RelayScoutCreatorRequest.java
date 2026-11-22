package com.expnai.relay.features.partners.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RelayScoutCreatorRequest {

	@NotBlank
	@Size(max = 255)
	private String name;

	@Size(max = 255)
	private String handle;

	@Size(max = 50)
	private String plat;

	@Size(max = 2000)
	private String url;

	@Size(max = 50)
	private String followers;

	@Size(max = 500)
	private String niche;

	@Size(max = 2000)
	private String signal;

	@Size(max = 50)
	private String list;

	@Size(max = 500)
	private String product;

	@NotBlank
	@Email
	@Size(max = 255)
	private String email;
}
