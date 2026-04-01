package io.metaloom.loom.rest.model.token;

import io.metaloom.loom.rest.model.RestRequestModel;
import io.metaloom.loom.rest.model.common.AbstractMetaModel;

public class TokenUpdateRequest extends AbstractMetaModel<TokenUpdateRequest> implements RestRequestModel {

	private String name;

	public String getName() {
		return name;
	}

	public TokenUpdateRequest setName(String name) {
		this.name = name;
		return this;
	}

	@Override
	public TokenUpdateRequest self() {
		return this;
	}

}
