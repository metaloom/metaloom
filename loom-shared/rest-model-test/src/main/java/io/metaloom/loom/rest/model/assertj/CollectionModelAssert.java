package io.metaloom.loom.rest.model.assertj;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.metaloom.loom.rest.model.collection.CollectionResponse;
import io.metaloom.loom.rest.model.junit.LoomAssertions;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.rest.validation.impl.LoomModelValidatorImpl;

public class CollectionModelAssert extends AbstractModelAssert<CollectionModelAssert, CollectionResponse> {

	private LoomModelValidator validator = new LoomModelValidatorImpl();

	public CollectionModelAssert(CollectionResponse actual) {
		super(actual, CollectionModelAssert.class);
	}

	public CollectionModelAssert isValid() {
		validator.validate(actual);
		return this;
	}

	public CollectionModelAssert matches(CollectionResponse response) {
		assertEquals(response.getUuid(), actual.getUuid(), "The UUID did not match");
		assertEquals(response.getName(), actual.getName(), "The name did not match");
		LoomAssertions.assertEqualsJson(response.getMeta(), actual.getMeta(), "The meta JSON did not match");
		return this;
	}

	public CollectionModelAssert hasName(String name) {
		assertEquals(name, actual.getName(), "The name did not match");
		return this;
	}

}
