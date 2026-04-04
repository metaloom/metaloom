package io.metaloom.loom.rest.model.assertj;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.metaloom.loom.rest.model.junit.LoomAssertions;
import io.metaloom.loom.rest.model.role.RoleResponse;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.rest.validation.impl.LoomModelValidatorImpl;

public class RoleModelAssert extends AbstractModelAssert<RoleModelAssert, RoleResponse> {

	private LoomModelValidator validator = new LoomModelValidatorImpl();

	public RoleModelAssert(RoleResponse actual) {
		super(actual, RoleModelAssert.class);
	}

	public RoleModelAssert isValid() {
		validator.validate(actual);
		return this;
	}

	public RoleModelAssert matches(RoleResponse response) {
		assertEquals(response.getUuid(), actual.getUuid(), "The UUID did not match");
		assertEquals(response.getName(), actual.getName(), "The name did not match");
		LoomAssertions.assertEqualsJson(response.getMeta(), actual.getMeta(), "The meta JSON did not match");
		return this;
	}

}
