package io.metaloom.loom.rest.model.assertj;

import io.metaloom.loom.rest.model.token.TokenResponse;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.rest.validation.impl.LoomModelValidatorImpl;

public class TokenModelAssert extends AbstractModelAssert<TokenModelAssert, TokenResponse> {

	private LoomModelValidator validator = new LoomModelValidatorImpl();

	public TokenModelAssert(TokenResponse actual) {
		super(actual, TokenModelAssert.class);
	}

	public TokenModelAssert isValid() {
		validator.validate(actual);
		return this;
	}

}
