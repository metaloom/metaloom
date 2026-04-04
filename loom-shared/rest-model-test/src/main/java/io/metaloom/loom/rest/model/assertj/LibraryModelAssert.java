package io.metaloom.loom.rest.model.assertj;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.metaloom.loom.rest.model.junit.LoomAssertions;
import io.metaloom.loom.rest.model.library.LibraryResponse;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.rest.validation.impl.LoomModelValidatorImpl;

public class LibraryModelAssert extends AbstractModelAssert<LibraryModelAssert, LibraryResponse> {

	private LoomModelValidator validator = new LoomModelValidatorImpl();

	public LibraryModelAssert(LibraryResponse actual) {
		super(actual, LibraryModelAssert.class);
	}

	public LibraryModelAssert isValid() {
		validator.validate(actual);
		return this;
	}

	public LibraryModelAssert matches(LibraryResponse response) {
		assertEquals(response.getUuid(), actual.getUuid(), "The UUID did not match");
		LoomAssertions.assertEqualsJson(response.getMeta(), actual.getMeta(), "The meta JSON did not match");
		return this;
	}

}
