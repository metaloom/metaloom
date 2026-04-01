package io.metaloom.loom.rest.model.token;

import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;

public class TokenResponse extends AbstractCreatorEditorRestResponse<TokenResponse> {

	private String name;

	private String token;

	public String getName() {
		return name;
	}

	public TokenResponse setName(String name) {
		this.name = name;
		return this;
	}

	public String getToken() {
		return token;
	}

	public TokenResponse setToken(String token) {
		this.token = token;
		return this;
	}

	@Override
	public TokenResponse self() {
		return this;
	}
}
