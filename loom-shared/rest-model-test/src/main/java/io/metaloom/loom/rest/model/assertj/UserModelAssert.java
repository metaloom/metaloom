package io.metaloom.loom.rest.model.assertj;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.metaloom.loom.rest.model.user.UserResponse;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.rest.validation.impl.LoomModelValidatorImpl;

public class UserModelAssert extends AbstractModelAssert<UserModelAssert, UserResponse> {

	private LoomModelValidator validator = new LoomModelValidatorImpl();

	public UserModelAssert(UserResponse actual) {
		super(actual, UserModelAssert.class);
	}

	public UserModelAssert isValid() {
		validator.validate(actual);
		return this;
	}

	public UserModelAssert matches(UserResponse user) {
		assertEquals(actual.getUsername(), user.getUsername(), "Username did not match up");
		assertEquals(actual.getFirstname(), user.getFirstname(), "Firstname did not match up");
		assertEquals(actual.getLastname(), user.getLastname(), "Lastname did not match up");

		assertEquals(actual.getUuid(), user.getUuid(), "UUID did not match up");
		return this;
	}

}
