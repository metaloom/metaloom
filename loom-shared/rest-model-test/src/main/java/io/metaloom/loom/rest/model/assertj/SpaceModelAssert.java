package io.metaloom.loom.rest.model.assertj;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.metaloom.loom.rest.model.junit.LoomAssertions;
import io.metaloom.loom.rest.model.space.SpaceResponse;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.rest.validation.impl.LoomModelValidatorImpl;

public class SpaceModelAssert extends AbstractModelAssert<SpaceModelAssert, SpaceResponse> {

	private LoomModelValidator validator = new LoomModelValidatorImpl();

	public SpaceModelAssert(SpaceResponse actual) {
		super(actual, SpaceModelAssert.class);
	}

	public SpaceModelAssert isValid() {
		validator.validate(actual);
		return this;
	}

	public SpaceModelAssert matches(SpaceResponse response) {
		assertEquals(response.getUuid(), actual.getUuid(), "The UUID did not match");
		LoomAssertions.assertEqualsJson(response.getMeta(), actual.getMeta(), "The meta JSON did not match");
		return this;
	}

}
