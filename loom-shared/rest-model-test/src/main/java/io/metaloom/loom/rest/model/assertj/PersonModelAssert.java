package io.metaloom.loom.rest.model.assertj;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.metaloom.loom.rest.model.person.PersonResponse;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.rest.validation.impl.LoomModelValidatorImpl;

public class PersonModelAssert extends AbstractModelAssert<PersonModelAssert, PersonResponse> {

	private LoomModelValidator validator = new LoomModelValidatorImpl();

	public PersonModelAssert(PersonResponse actual) {
		super(actual, PersonModelAssert.class);
	}

	public PersonModelAssert isValid() {
		validator.validate(actual);
		return this;
	}

	public PersonModelAssert matches(PersonResponse response) {
		assertEquals(response.getAlias(), actual.getAlias(), "The alias did not match");
		assertEquals(response.getFirstname(), actual.getFirstname(), "The firstname did not match");
		assertEquals(response.getLastname(), actual.getLastname(), "The lastname did not match");
		return this;
	}

}
