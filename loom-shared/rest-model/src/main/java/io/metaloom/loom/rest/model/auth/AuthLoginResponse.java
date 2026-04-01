package io.metaloom.loom.rest.model.auth;

import io.metaloom.loom.rest.model.RestResponseModel;

public class AuthLoginResponse implements RestResponseModel<AuthLoginResponse> {

	private String token;

	public String getToken() {
		return token;
	}

	public void setToken(String token) {
		this.token = token;
	}

	@Override
	public AuthLoginResponse self() {
		return this;
	}
}
