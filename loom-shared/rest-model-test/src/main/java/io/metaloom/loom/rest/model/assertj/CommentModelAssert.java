package io.metaloom.loom.rest.model.assertj;

import io.metaloom.loom.rest.model.comment.CommentResponse;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.rest.validation.impl.LoomModelValidatorImpl;

public class CommentModelAssert extends AbstractModelAssert<CommentModelAssert, CommentResponse> {

	private LoomModelValidator validator = new LoomModelValidatorImpl();

	public CommentModelAssert(CommentResponse actual) {
		super(actual, CommentModelAssert.class);
	}

	public CommentModelAssert isValid() {
		validator.validate(actual);
		return this;
	}

}
