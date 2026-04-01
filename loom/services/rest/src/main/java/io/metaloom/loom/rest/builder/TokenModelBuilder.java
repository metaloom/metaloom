package io.metaloom.loom.rest.builder;

import io.metaloom.loom.db.model.token.Token;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.token.TokenListResponse;
import io.metaloom.loom.rest.model.token.TokenResponse;

public interface TokenModelBuilder extends ModelBuilder, UserModelBuilder {

	default TokenResponse toResponse(Token token) {
		return toResponse(token, false);
	}

	default TokenResponse toResponseWithToken(Token token) {
		return toResponse(token, true);
	}

	default TokenResponse toResponse(Token token, boolean exposeToken) {
		TokenResponse response = new TokenResponse();
		response.setUuid(token.getUuid());
		response.setName(token.getName());
		if (exposeToken) {
			response.setToken(token.getToken());
		}
		setStatus(token, response);
		return response;
	}

	default TokenListResponse toTokenList(Page<Token> page) {
		return setPage(new TokenListResponse(), page, token -> {
			return toResponse(token, false);
		});
	}
}
