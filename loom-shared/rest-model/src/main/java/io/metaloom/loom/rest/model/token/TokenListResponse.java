package io.metaloom.loom.rest.model.token;

import io.metaloom.loom.rest.model.common.AbstractListResponse;

public class TokenListResponse extends AbstractListResponse<TokenListResponse, TokenResponse> {

	@Override
	public TokenListResponse self() {
		return this;
	}

}
