package io.metaloom.loom.rest.model.assertj;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.metaloom.loom.rest.model.attachment.AttachmentResponse;
import io.metaloom.loom.rest.validation.LoomModelValidator;
import io.metaloom.loom.rest.validation.impl.LoomModelValidatorImpl;

public class AttachmentModelAssert extends AbstractModelAssert<AttachmentModelAssert, AttachmentResponse> {

	private LoomModelValidator validator = new LoomModelValidatorImpl();

	public AttachmentModelAssert(AttachmentResponse actual) {
		super(actual, AttachmentModelAssert.class);
	}

	public AttachmentModelAssert isValid() {
		validator.validate(actual);
		return this;
	}

	public AttachmentModelAssert matches(AttachmentResponse response) {
		assertEquals(response.getFilename(), actual.getFilename(), "The filename did not match");
		assertEquals(response.getSha512sum(), actual.getSha512sum(), "The sha512sum did not match");
		assertEquals(response.getMimeType(), actual.getMimeType(), "The mimetype did not match");
		assertEquals(response.getSize(), actual.getSize(), "The size did not match");
		return this;
	}
}
