package io.metaloom.loom.rest.model.assertj;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.metaloom.loom.rest.model.annotation.AnnotationResponse;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.rest.validation.impl.LoomModelValidatorImpl;

public class AnnotationModelAssert extends AbstractModelAssert<AnnotationModelAssert, AnnotationResponse> {

	private LoomModelValidator validator = new LoomModelValidatorImpl();

	public AnnotationModelAssert(AnnotationResponse actual) {
		super(actual, AnnotationModelAssert.class);
	}

	public AnnotationModelAssert isValid() {
		validator.validate(actual);
		return this;
	}

	public AnnotationModelAssert matches(AnnotationResponse response) {
		assertEquals(actual.getTitle(), response.getTitle(), "The title does not match up");
		assertEquals(actual.getDescription(), response.getDescription(), "The decription does not match up");
		return this;
	}

}
