package io.metaloom.loom.rest.model.token;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;

public class TokenCreateRequest extends AbstractMetaModel<TokenCreateRequest> implements RestRequestModel {

	private String name;
	
	private String token;

	public String getName() {
		return name;
	}

	public TokenCreateRequest setName(String name) {
		this.name = name;
		return this;
	}

	public String getToken() {
		return token;
	}

	public TokenCreateRequest setToken(String token) {
		this.token = token;
		return this;
	}

	@Override
	public TokenCreateRequest self() {
		return this;
	}

}
