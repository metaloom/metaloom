package io.metaloom.loom.rest.model.token;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.metaloom.loom.rest.model.user.UserExamples;
import io.metaloom.utils.StringUtils;
import io.netty.handler.codec.http.HttpResponseStatus;

public interface TokenExamples extends ExampleValues, UserExamples {

	default Example tokenUpdateExample() {
		return new ExampleImpl(tokenUpdateRequest(), "The token update request", HttpResponseStatus.OK);
	}

	default Example tokenCreateExample() {
		return new ExampleImpl(tokenCreateRequest(), "The token create request", HttpResponseStatus.CREATED);
	}

	default Example tokenResponseExample() {
		return new ExampleImpl(tokenResponse(), "The token response", HttpResponseStatus.OK);
	}

	default Example tokenListResponseExample() {
		return new ExampleImpl(tokenListResponse(), "The token list response", HttpResponseStatus.OK);
	}

	default TokenResponse tokenResponse() {
		TokenResponse response = new TokenResponse();
		response.setMeta(meta());
		response.setName("Primary Token");
		response.setToken(StringUtils.randomHumanString(8));
		response.setUuid(uuidC());
		setCreatorEditor(response);
		return response;
	}

	default TokenListResponse tokenListResponse() {
		TokenListResponse model = new TokenListResponse();
		model.setMetainfo(pagingInfo());
		model.add(tokenResponse());
		model.add(tokenResponse());
		return model;
	}

	default TokenUpdateRequest tokenUpdateRequest() {
		TokenUpdateRequest model = new TokenUpdateRequest();
		model.setName("New Token Name");
		model.setMeta(meta());
		return model;
	}

	default TokenCreateRequest tokenCreateRequest() {
		TokenCreateRequest model = new TokenCreateRequest();
		model.setName("The Token Name");
		model.setMeta(meta());
		return model;
	}
}
